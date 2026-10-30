"""Real SDK spans prove propagation and content-free export without a collector."""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import Status, StatusCode
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


# 创建带隔离存储及内部令牌的追踪测试配置。
def settings() -> AgentSettings:
    return AgentSettings.model_validate(
        {
            "pulseflow_agent_env": "test",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_internal_token": SecretStr("SECRET_TOKEN_SENTINEL"),
            "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
        }
    )


# 验证 HTTP 父上下文传递到正式 Agent span 和 Agent 数据库 span。
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


# 验证工具与 HTTP 客户端把同一 trace 传至 Java。
@pytest.mark.asyncio
async def test_tool_and_http_client_propagate_the_same_trace_to_java() -> None:
    exporter = InMemorySpanExporter()
    telemetry = Telemetry(exporter=exporter)
    headers: list[str] = []
    period = {
        "fromInclusive": "2026-09-01T00:00:00+08:00",
        "toExclusive": "2026-09-08T00:00:00+08:00",
    }

    # 模拟需要调用 Java 工具的调查模型，驱动跨服务追踪链路。
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

    # 记录请求的 trace 上下文并返回安全聚合结果。
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


def test_otlp_http_wire_is_content_free_and_preserves_error_and_parent() -> None:
    """Check the actual protobuf exporter, including errors and Resource attributes."""
    received: list[tuple[str, bytes]] = []

    class Receiver(BaseHTTPRequestHandler):
        # 接收测试 OTLP HTTP 导出载荷并保存，供父子关系和隐私断言检查。
        def do_POST(self) -> None:
            received.append((self.path, self.rfile.read(int(self.headers["Content-Length"]))))
            self.send_response(200)
            self.end_headers()

        # 关闭测试接收器默认 HTTP 日志，避免污染测试输出。
        def log_message(self, _format: str, *args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    telemetry = Telemetry(
        endpoint=f"http://127.0.0.1:{server.server_port}/",
        provider=TracerProvider(resource=Resource({
            "service.name": "pulseflow-agent", "private.resource": "PRIVATE_RESOURCE_SENTINEL",
        })),
    )
    try:
        tracer = telemetry.provider.get_tracer("privacy-wire-test")
        with tracer.start_as_current_span("parent") as parent:
            with tracer.start_as_current_span("failed-operation") as span:
                for key in (
                    "gen_ai.input.messages", "gen_ai.output.messages", "gen_ai.system_instructions",
                    "gen_ai.tool.call.arguments", "gen_ai.tool.call.result", "db.statement",
                    "db.query.text", "db.query.parameters", "http.request.header.authorization",
                    "http.request.header.x_pulseflow_agent_token", "url.full",
                ):
                    span.set_attribute(key, "PRIVATE_CONTENT_SENTINEL")
                span.set_attribute("tool.name", "query_metric")
                span.record_exception(RuntimeError("PRIVATE_EXCEPTION_SENTINEL"))
                span.set_status(Status(StatusCode.ERROR, "PRIVATE_STATUS_SENTINEL"))
                expected_trace = span.get_span_context().trace_id.to_bytes(16, "big")
            expected_parent = parent.get_span_context().span_id.to_bytes(8, "big")
        assert telemetry.provider.force_flush(timeout_millis=10000)
        assert len(received) == 1
        path, body = received[0]
        assert path == "/v1/traces"  # exactly once, including trailing slash in base URL
        assert b"PRIVATE_" not in body
        request = ExportTraceServiceRequest.FromString(body)
        resource_spans = request.resource_spans[0]
        exported = next(
            item for scope in resource_spans.scope_spans for item in scope.spans
            if item.name == "failed-operation"
        )
        assert exported.trace_id == expected_trace
        assert exported.parent_span_id == expected_parent
        assert exported.status.code == 2  # ERROR survives without the exception description
        assert not exported.status.message
        assert not exported.events
        assert [item.key for item in exported.attributes] == ["tool.name"]
        assert [item.key for item in resource_spans.resource.attributes] == ["service.name"]
    finally:
        telemetry.shutdown()
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)
