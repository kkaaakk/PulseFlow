"""Typed read-only Java business client for investigation tools."""

from time import monotonic
from typing import TypeVar

import httpx
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from pydantic import ValidationError

from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.contracts import (
    AttributionArgs,
    AttributionResponse,
    AudienceResponse,
    BreakdownMetricArgs,
    CompareMetricArgs,
    CompareResponse,
    PerformanceResponse,
    PreviewAudienceArgs,
    QueryMetricArgs,
    QueryResponse,
    WireModel,
)
from pulseflow_agent.observability.tracing import QualityMetrics

ResponseT = TypeVar("ResponseT", bound=WireModel)
_PREFIX = "/internal/v1/agent-tools"


# Java 工具调用失败的安全错误码，禁止携带原始响应正文。
class ToolClientError(Exception):
    """Content-free error code suitable for returning to the model."""

    # 仅保存安全错误码，避免模型或日志收到原始 HTTP 错误正文。
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


# 类型化 Java 只读工具客户端，管理鉴权、契约校验和短时熔断。
class PulseFlowApiClient:
    # 保存 Java 地址、令牌和共享 HTTP 客户端，初始化连续失败与熔断状态。
    def __init__(
        self,
        settings: AgentSettings,
        client: httpx.AsyncClient,
        metrics: QualityMetrics | None = None,
    ) -> None:
        self._base_url = str(settings.pulseflow_java_base_url).rstrip("/")
        self._token = settings.pulseflow_agent_internal_token
        self._client = client
        self._metrics = metrics
        self._failures = 0
        self._open_until = 0.0

    # 累计失败并更新指标；连续三次失败后熔断十五秒，返回安全错误码。
    def _unavailable(self) -> ToolClientError:
        self._failures += 1
        if self._failures >= 3:
            self._open_until = monotonic() + 15
        if self._metrics:
            self._metrics.java_call(True)
        return ToolClientError("java_tool_unavailable")

    # 发送携带令牌和 trace 的请求并校验响应类型；熔断期间直接拒绝调用。
    async def _request(
        self,
        method: str,
        path: str,
        response_type: type[ResponseT],
        payload: WireModel | None = None,
    ) -> ResponseT:
        if monotonic() < self._open_until:
            raise ToolClientError("java_tool_circuit_open")
        if self._token is None or not self._token.get_secret_value():
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("java_tool_unavailable")
        headers = {"X-PulseFlow-Agent-Token": self._token.get_secret_value()}
        TraceContextTextMapPropagator().inject(headers)
        try:
            response = await self._client.request(
                method,
                self._base_url + _PREFIX + path,
                headers=headers,
                json=(
                    payload.model_dump(mode="json", by_alias=True) if payload is not None else None
                ),
                timeout=10.0,
            )
        except httpx.HTTPError:
            raise self._unavailable() from None
        if 400 <= response.status_code < 500:
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("java_tool_rejected_arguments")
        if response.status_code != 200:
            raise self._unavailable()
        self._failures = 0
        self._open_until = 0.0
        try:
            result = response_type.model_validate(response.json())
        except (ValidationError, ValueError, TypeError):
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("invalid_java_tool_response") from None
        if self._metrics:
            self._metrics.java_call(False)
        return result

    # 查询指标并核对响应指标、维度和行数，阻止不符合契约的数据进入模型。
    async def query_metric(self, args: QueryMetricArgs) -> QueryResponse:
        response = await self._request("POST", "/metrics/query", QueryResponse, args)
        if (
            response.metric != args.metric
            or response.dimensions != args.dimensions
            or len(response.rows) > args.row_limit
        ):
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    # 读取周期对比，验证指标类型、分组维度和返回行数上限。
    async def compare_metric(self, args: CompareMetricArgs) -> CompareResponse:
        response = await self._request("POST", "/metrics/compare", CompareResponse, args)
        if (
            response.metric != args.metric
            or response.dimensions != args.dimensions
            or len(response.rows) > args.row_limit
        ):
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    # 读取单维度拆解，确保响应维度及行数与请求一致。
    async def breakdown_metric(self, args: BreakdownMetricArgs) -> QueryResponse:
        response = await self._request("POST", "/metrics/breakdown", QueryResponse, args)
        if (
            response.metric != args.metric
            or response.dimensions != [args.dimension]
            or len(response.rows) > args.row_limit
        ):
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    # 校验活动 ID 后读取绩效，拒绝活动归属不匹配的响应。
    async def get_campaign_performance(self, campaign_id: int) -> PerformanceResponse:
        if campaign_id <= 0:
            raise ToolClientError("java_tool_rejected_arguments")
        response = await self._request(
            "GET", f"/campaigns/{campaign_id}/performance", PerformanceResponse
        )
        if response.campaign_id != campaign_id:
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    # 读取聚合归因，校验响应维度与行数限制后返回类型化结果。
    async def get_attribution_breakdown(self, args: AttributionArgs) -> AttributionResponse:
        response = await self._request("POST", "/attribution/breakdown", AttributionResponse, args)
        if response.dimension != args.dimension or len(response.rows) > args.row_limit:
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    # 把类型化 DSL 交给 Java 做只读预估，返回校验及人数结果。
    async def preview_audience(self, args: PreviewAudienceArgs) -> AudienceResponse:
        return await self._request("POST", "/audience/preview", AudienceResponse, args)
