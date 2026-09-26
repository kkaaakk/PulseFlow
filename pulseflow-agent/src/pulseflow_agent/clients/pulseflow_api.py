"""Typed, read-only client for Java's fixed business Tool endpoints."""

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


class ToolClientError(Exception):
    """Content-free error code suitable for returning to the model."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class PulseFlowApiClient:
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

    async def _request(
        self,
        method: str,
        path: str,
        response_type: type[ResponseT],
        payload: WireModel | None = None,
    ) -> ResponseT:
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
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("java_tool_unavailable") from None
        if 400 <= response.status_code < 500:
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("java_tool_rejected_arguments")
        if response.status_code != 200:
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("java_tool_unavailable")
        try:
            result = response_type.model_validate(response.json())
        except (ValidationError, ValueError, TypeError):
            if self._metrics:
                self._metrics.java_call(True)
            raise ToolClientError("invalid_java_tool_response") from None
        if self._metrics:
            self._metrics.java_call(False)
        return result

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

    async def get_attribution_breakdown(self, args: AttributionArgs) -> AttributionResponse:
        response = await self._request("POST", "/attribution/breakdown", AttributionResponse, args)
        if response.dimension != args.dimension or len(response.rows) > args.row_limit:
            if self._metrics:
                self._metrics.java_contract_error()
            raise ToolClientError("invalid_java_tool_response")
        return response

    async def preview_audience(self, args: PreviewAudienceArgs) -> AudienceResponse:
        return await self._request("POST", "/audience/preview", AudienceResponse, args)
