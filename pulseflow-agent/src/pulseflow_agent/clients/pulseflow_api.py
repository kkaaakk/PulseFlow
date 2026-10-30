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
    UnsafeToolValueError,
    WireModel,
)
from pulseflow_agent.observability.tracing import QualityMetrics
from pulseflow_agent.security.pii_guardrail import PiiBlockedError

ResponseT = TypeVar("ResponseT", bound=WireModel)
_PREFIX = "/internal/v1/agent-tools"


class ToolClientError(Exception):
    """Content-free error code suitable for returning to the model."""

    # 只保存可安全返回模型的稳定错误码，不携带原始 HTTP 响应或敏感业务内容。
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class PulseFlowApiClient:
    # 保存 Java 地址、内部令牌、共享异步客户端及可选质量指标，并初始化本实例的熔断状态。
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

    # 累计依赖失败；连续失败达到三次时熔断十五秒，记录错误指标并返回稳定的不可用异常。
    def _unavailable(self) -> ToolClientError:
        self._failures += 1
        if self._failures >= 3:
            self._open_until = monotonic() + 15
        if self._metrics:
            self._metrics.java_call(True)
        return ToolClientError("java_tool_unavailable")

    # 携带内部令牌及 trace 上下文执行十秒超时请求，检查状态并按 response_type 校验响应；
    # 不安全值转为 PII 阻断。
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
        except UnsafeToolValueError:
            raise PiiBlockedError("blocked_tool_value") from None
        except (ValidationError, ValueError, TypeError):
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("invalid_java_tool_response") from None
        if self._metrics:
            self._metrics.java_call(False)
        return result

    # 调用 Java 聚合指标接口，并核对响应指标、维度及行数上限与 args 一致。
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

    # 请求 Java 计算双窗口变化，并校验返回指标、维度与行数符合请求契约。
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

    # 请求单维度指标拆解，核对返回维度、指标和行数，拒绝不符合约定的响应。
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

    # 仅接受正数 campaign_id，读取预计算绩效并确认响应属于同一 Campaign。
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

    # 请求归因汇总并核对维度与行数上限，不允许响应扩大查询范围。
    async def get_attribution_breakdown(self, args: AttributionArgs) -> AttributionResponse:
        response = await self._request("POST", "/attribution/breakdown", AttributionResponse, args)
        if response.dimension != args.dimension or len(response.rows) > args.row_limit:
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    # 向只读 Java 接口提交类型化 DSL，返回校验和聚合人群预估结果。
    async def preview_audience(self, args: PreviewAudienceArgs) -> AudienceResponse:
        return await self._request("POST", "/audience/preview", AudienceResponse, args)
