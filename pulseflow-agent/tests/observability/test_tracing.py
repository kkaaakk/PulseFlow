"""Real SDK spans prove propagation and content-free export without a collector."""

import json
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import SecretStr
from pydantic_ai import ModelMessage, ModelResponse, ToolCallPart, ToolReturnPart
from pydantic_ai.models.function import AgentInfo, FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.investigation import ToolObservation
from pulseflow_agent.main import create_app
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


# 构造隔离测试配置，使用测试数据库和虚拟机器令牌，避免依赖开发环境凭据。
def settings() -> AgentSettings:
    return AgentSettings.model_validate(
        {
            "pulseflow_agent_env": "test",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_internal_token": SecretStr("SECRET_TOKEN_SENTINEL"),
            "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
        }
    )


# 验证 HTTP 父 trace 传播到官方 Agent span 与调查数据库调用。
def test_http_parent_reaches_official_agent_run_and_agent_db() -> None:
    exporter = InMemorySpanExporter()
    telemetry = Telemetry(exporter=exporter)
    trace_id = "1234567890abcdef1234567890abcdef"
    with TestClient(create_app(settings(), telemetry)) as http:
        response = http.post(
            "/internal/v1/investigations",
            json={"question": "PRIVATE_GOAL_SENTINEL"},
            headers={
                "X-PulseFlow-Agent-Token": "SECRET_TOKEN_SENTINEL",
                "traceparent": f"00-{trace_id}-1234567890abcdef-01",
            },
        )
        assert response.status_code == 200
    spans = [
        span
        for span in exporter.get_finished_spans()
        if f"{span.context.trace_id:032x}" == trace_id
    ]
    assert any(
        (span.attributes or {}).get("gen_ai.operation.name") == "invoke_agent" for span in spans
    )
    assert any((span.attributes or {}).get("db.system") == "sqlite" for span in spans)
    serialized = str([(span.name, span.attributes, span.events, span.status) for span in spans])
    assert "PRIVATE_GOAL_SENTINEL" not in serialized
    assert "SECRET_TOKEN_SENTINEL" not in serialized
    assert "INSERT INTO" not in serialized


# 验证工具 span 和 HTTP 客户端向 Java 传播同一条 trace。
@pytest.mark.asyncio
async def test_tool_and_http_client_propagate_the_same_trace_to_java() -> None:
    exporter = InMemorySpanExporter()
    telemetry = Telemetry(exporter=exporter)
    headers: list[str] = []
    period = {
        "fromInclusive": "2026-09-01T00:00:00+08:00",
        "toExclusive": "2026-09-08T00:00:00+08:00",
    }

    # 模拟一次业务工具选择及最终响应，供跨服务 trace 测试使用。
    async def model(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        results = [
            part
            for message in messages
            for part in message.parts
            if isinstance(part, ToolReturnPart)
        ]
        if not results:
            return ModelResponse(
                parts=[
                    ToolCallPart(
                        "query_metric",
                        {
                            "request": {
                                "metric": "CTR",
                                "time_range": period,
                            }
                        },
                    )
                ]
            )
        value = results[-1].content
        observation = (
            ToolObservation.model_validate_json(value)
            if isinstance(value, str)
            else ToolObservation.model_validate(value)
        )
        return ModelResponse(
            parts=[
                ToolCallPart(
                    info.output_tools[0].name,
                    {
                        "status": "DIAGNOSED",
                        "summary": "PRIVATE_OUTPUT_SENTINEL",
                        "findings": [],
                        "evidence_ids": [observation.evidence_id],
                        "unresolved_questions": [],
                        "confidence": "medium",
                        "recommended_next_action": None,
                    },
                )
            ]
        )

    # 按当前测试场景提供 Java 聚合接口替身，检查调用路径及输入并返回受控事实。
    def java(request: httpx.Request) -> httpx.Response:
        headers.append(request.headers["traceparent"])
        return httpx.Response(
            200,
            json={
                "metadata": {
                    "queryId": str(uuid4()),
                    "generatedAt": "2026-09-26T00:00:00Z",
                    "dataVersion": None,
                    "source": "campaign-facts",
                    "warnings": [],
                },
                "metric": "CTR",
                "timeRange": period,
                "dimensions": [],
                "sampleSize": 10,
                "rows": [{"dimensions": {}, "value": "0.1", "sampleSize": 10}],
            },
        )

    config = settings()
    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        HTTPXClientInstrumentor.instrument_client(http, tracer_provider=telemetry.provider)
        result = await GrowthInvestigator(
            config,
            AzurePiiGuardrail(config, http),
            PulseFlowApiClient(config, http, telemetry.metrics),
            model=FunctionModel(model),
            telemetry=telemetry,
        ).run("PRIVATE_GOAL_SENTINEL")
    spans = exporter.get_finished_spans()
    trace_id = headers[0].split("-")[1]
    assert result.evidence[0].trace_id == trace_id
    operations = {(span.attributes or {}).get("gen_ai.operation.name") for span in spans}
    assert {"invoke_agent", "execute_tool", "chat"}.issubset(operations)
    assert any(span.kind.name == "CLIENT" for span in spans)
    serialized = json.dumps([dict(span.attributes or {}) for span in spans])
    assert "PRIVATE_GOAL_SENTINEL" not in serialized
    assert "PRIVATE_OUTPUT_SENTINEL" not in serialized
    assert "SECRET_TOKEN_SENTINEL" not in serialized
    metrics = telemetry.metrics.snapshot()
    assert metrics["avg_tool_calls"] == 1
    assert metrics["avg_model_requests"] == 2
    assert metrics["avg_latency"] is not None
    telemetry.shutdown()
