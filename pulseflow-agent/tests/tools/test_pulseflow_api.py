"""Java HTTP boundary: fixed endpoints, strict response validation, safe errors."""

from datetime import datetime
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient, ToolClientError
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.contracts import QueryMetricArgs


def settings() -> AgentSettings:
    return AgentSettings.model_validate({
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("test-secret"),
        "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
    })


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


def args() -> QueryMetricArgs:
    return QueryMetricArgs.model_validate({
        "metric": "CTR", "timeRange": {
            "fromInclusive": "2026-09-01T00:00:00+08:00",
            "toExclusive": "2026-09-08T00:00:00+08:00",
        },
    })


@pytest.mark.asyncio
async def test_query_has_only_internal_token_and_typed_aggregate_response() -> None:
    calls: list[httpx.Request] = []

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


@pytest.mark.asyncio
async def test_unknown_response_field_is_rejected_instead_of_reaching_model() -> None:
    payload = query_response()
    payload["userId"] = 12345
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=payload)
    )) as http:
        with pytest.raises(ToolClientError, match="invalid_java_tool_response"):
            await PulseFlowApiClient(settings(), http).query_metric(args())


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
