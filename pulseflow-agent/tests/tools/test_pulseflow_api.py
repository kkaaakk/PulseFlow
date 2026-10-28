"""Java HTTP boundary: fixed endpoints, strict response validation, safe errors."""

from datetime import datetime
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient, ToolClientError
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.contracts import QueryMetricArgs


# 构造隔离测试配置，使用测试数据库和虚拟机器令牌，避免依赖开发环境凭据。
def settings() -> AgentSettings:
    return AgentSettings.model_validate({
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("test-secret"),
        "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
    })


# 构造合法聚合响应夹具，用于客户端类型及请求契约校验。
def query_response() -> dict[str, object]:
    return {
        "metadata": {
            "queryId": str(uuid4()), "generatedAt": "2026-09-25T00:00:00Z",
            "dataVersion": None, "source": "campaign-facts", "warnings": [],
        },
        "metric": "CTR",
        "timeRange": {
            "fromInclusive": "2026-09-01T00:00:00+08:00",
            "toExclusive": "2026-09-08T00:00:00+08:00",
        },
        "dimensions": [],
        "rows": [{"dimensions": {}, "value": "0.2500", "sampleSize": 100}],
        "sampleSize": 100,
    }


# 构造带时区、有界时间范围的类型化指标请求。
def args() -> QueryMetricArgs:
    return QueryMetricArgs.model_validate({
        "metric": "CTR", "timeRange": {
            "fromInclusive": "2026-09-01T00:00:00+08:00",
            "toExclusive": "2026-09-08T00:00:00+08:00",
        },
    })


# 验证 Java 请求只用内部令牌，返回严格类型化聚合结果。
@pytest.mark.asyncio
async def test_query_has_only_internal_token_and_typed_aggregate_response() -> None:
    calls: list[httpx.Request] = []

    # 模拟当前用例的 HTTP 响应并检查请求约定，隔离外部 Azure 或 Java 服务。
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=query_response())

    config = settings()
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        result = await PulseFlowApiClient(config, http).query_metric(args())
    assert result.rows[0].value == 0.25
    assert calls[0].url.path == "/internal/v1/agent-tools/metrics/query"
    assert calls[0].headers["X-PulseFlow-Agent-Token"] == "test-secret"
    assert "operatorId" not in calls[0].content.decode()
    assert "userId" not in result.model_dump_json()


# 验证 HTTP 失败转换为安全错误码，不泄露响应正文。
@pytest.mark.asyncio
@pytest.mark.parametrize("status,expected", [(400, "java_tool_rejected_arguments"),
                                              (401, "java_tool_rejected_arguments"),
                                              (500, "java_tool_unavailable")])
async def test_http_failures_have_content_free_errors(status: int, expected: str) -> None:
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(status, text="raw userId 123 and internal SQL")
    )) as http:
        with pytest.raises(ToolClientError) as error:
            await PulseFlowApiClient(settings(), http).query_metric(args())
    assert error.value.code == expected
    assert "123" not in str(error.value)


# 验证未知响应字段被类型契约拒绝，不能进入模型上下文。
@pytest.mark.asyncio
async def test_unknown_response_field_is_rejected_instead_of_reaching_model() -> None:
    payload = query_response()
    payload["userId"] = 12345
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=payload)
    )) as http:
        with pytest.raises(ToolClientError, match="invalid_java_tool_response"):
            await PulseFlowApiClient(settings(), http).query_metric(args())


# 验证下游返回行数超过请求上限时被拒绝。
@pytest.mark.asyncio
async def test_java_cannot_exceed_requested_row_limit() -> None:
    payload = query_response()
    payload["rows"] = [
        {"dimensions": {}, "value": "0.1", "sampleSize": 10},
        {"dimensions": {}, "value": "0.2", "sampleSize": 10},
    ]
    request = args().model_copy(update={"row_limit": 1})
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=payload)
    )) as http:
        with pytest.raises(ToolClientError, match="invalid_java_tool_response"):
            await PulseFlowApiClient(settings(), http).query_metric(request)


# 验证无时区或过长时间范围无法构造合法请求。
def test_typed_request_rejects_naive_or_excessive_time_range() -> None:
    with pytest.raises(ValidationError):
        QueryMetricArgs.model_validate({
            "metric": "CTR", "timeRange": {
                "fromInclusive": datetime(2026, 9, 1).isoformat(),
                "toExclusive": datetime(2026, 9, 8).isoformat(),
            },
        })
    with pytest.raises(ValidationError):
        QueryMetricArgs.model_validate({
            "metric": "CTR", "timeRange": {
                "fromInclusive": "2026-09-01T00:00:00+08:00",
                "toExclusive": "2026-10-03T00:00:00+08:00",
            },
        })
