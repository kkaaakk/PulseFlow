"""Real HTTP/OTLP/MySQL acceptance using the existing offline model fixture.

The production Agent keeps its configured model. This one-shot test process starts
the same FastAPI app, injecting FunctionModel only through the existing test seam.
No Java business response, authentication check or exporter is mocked.
"""

import json
import os
import threading
import time
from pathlib import Path
from unittest.mock import patch

import httpx
import uvicorn
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.trace import Status, StatusCode
from pydantic_ai import ToolCallPart, models
from pydantic_ai.models.function import FunctionModel

from pulseflow_agent.config import AgentSettings
from pulseflow_agent.main import create_app
from pulseflow_agent.observability.tracing import Telemetry
from tests.evals.harness import EvalCase, OfflinePolicy

SENTINELS = (
    "PRIVATE_PROMPT_SENTINEL", "PRIVATE_OUTPUT_SENTINEL", "PRIVATE_TOOL_ARGS_SENTINEL",
    "PRIVATE_TOOL_RETURN_SENTINEL", "PRIVATE_SQL_SENTINEL", "PRIVATE_TOKEN_SENTINEL",
    "PRIVATE_EXCEPTION_SENTINEL", "PRIVATE_STATUS_SENTINEL", "PRIVATE_URL_SENTINEL",
)
FORBIDDEN = {
    "db.statement", "db.query.text", "db.query.parameters", "exception.message",
    "exception.stacktrace", "gen_ai.input.messages", "gen_ai.output.messages",
    "gen_ai.tool.call.arguments", "gen_ai.tool.call.result",
}


def wait_ready(client: httpx.Client, url: str) -> None:
    for _ in range(90):
        try:
            if client.get(url).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(1)
    raise AssertionError("service readiness timed out")


def read_trace(client: httpx.Client, trace_id: str, predicate):
    for _ in range(60):
        response = client.get(f"http://jaeger:16686/api/traces/{trace_id}")
        if response.status_code == 200:
            data = response.json().get("data", [])
            if data and predicate(data[0]):
                return data[0]
        time.sleep(1)
    raise AssertionError("Jaeger did not receive the required spans")


def tags(span):
    return {item["key"]: item["value"] for item in span.get("tags", [])}


def service(trace, span):
    return trace["processes"][span["processID"]]["serviceName"]


def parent(span):
    return next((r["spanID"] for r in span["references"] if r["refType"] == "CHILD_OF"), None)


def check_privacy(trace, token):
    raw = json.dumps(trace, ensure_ascii=False)
    assert all(value not in raw for value in (*SENTINELS, token)), "private value exported"
    assert "最近活动转化率下降了" not in raw, "raw user input exported"
    for span in trace["spans"]:
        attributes = tags(span)
        assert not FORBIDDEN.intersection(attributes), "private attribute exported"
        assert not any("header" in key for key in attributes), "HTTP header exported"
        assert not span.get("logs"), "span events exported"
        process = trace["processes"][span["processID"]]
        assert all(t["key"] in {"service.name", "service.version"}
                   or t["key"].startswith("telemetry.sdk.") for t in process.get("tags", [])), \
            "non-allowlisted resource exported"


def check_chain(trace, trace_id):
    spans = trace["spans"]
    by_id = {span["spanID"]: span for span in spans}
    assert all(span["traceID"] == trace_id for span in spans), "trace IDs differ"
    assert all(parent(s) is None or parent(s) in by_id for s in spans), "parent span missing"

    def ancestors(span):
        seen = set()
        current = parent(span)
        while current in by_id:
            assert current not in seen, "cyclic span parents"
            seen.add(current)
            yield by_id[current]
            current = parent(by_id[current])

    java_http = next(s for s in spans if service(trace, s) == "pulseflow-java"
                     and tags(s).get("span.kind") == "server")
    python_http = by_id[parent(java_http)]
    assert service(trace, python_http) == "pulseflow-agent"
    assert tags(python_http).get("span.kind") == "client", "Java parent is not HTTPX"
    lineage = list(ancestors(python_http))
    operations = {tags(s).get("gen_ai.operation.name") for s in lineage}
    assert {"execute_tool", "invoke_agent"}.issubset(operations), "Agent/Tool parent missing"
    assert any(tags(s).get("span.kind") == "server" for s in lineage), "FastAPI parent missing"
    assert any(tags(s).get("gen_ai.operation.name") == "chat" for s in spans), "model span missing"
    jdbc = next(s for s in spans if service(trace, s) == "pulseflow-java"
                and (tags(s).get("db.system") or tags(s).get("db.system.name")) == "mysql")
    jdbc_lineage = list(ancestors(jdbc))
    assert java_http in jdbc_lineage, "JDBC is outside Java request"
    assert any(s["operationName"] == "agent-tools.metric-query" for s in jdbc_lineage), \
        "Java Service parent missing"
    assert any(service(trace, s) == "pulseflow-agent"
               and (tags(s).get("db.system") or tags(s).get("db.system.name")) == "mysql"
               for s in spans), "Agent MySQL span missing"


def run():
    models.ALLOW_MODEL_REQUESTS = False
    settings = AgentSettings()
    assert settings.is_test_model, "acceptance requires offline test configuration"
    assert os.getenv("RUNNING_AGENT_MODEL", "test") == "test", \
        "offline acceptance requires the running Agent to use TestModel"
    token = settings.pulseflow_agent_internal_token.get_secret_value()
    headers = {"X-PulseFlow-Agent-Token": token}
    telemetry = Telemetry()
    cases = json.loads((Path("/app/tests/evals/cases.json")).read_text(encoding="utf-8"))
    case = next(EvalCase.model_validate(c) for c in cases if c["fixtures"]["mode"] == "query")
    policy = OfflinePolicy(case)

    async def offline(messages, info):
        response = await policy.respond(messages, info)
        for part in response.parts:
            if isinstance(part, ToolCallPart) and part.tool_name == info.output_tools[0].name:
                part.args["summary"] = "PRIVATE_OUTPUT_SENTINEL"
        return response

    server = uvicorn.Server(uvicorn.Config(
        create_app(settings, telemetry), host="127.0.0.1", port=8002,
        log_level="critical", access_log=False,
    ))
    with httpx.Client(timeout=120, trust_env=False) as client:
        wait_ready(client, "http://agent:8001/health/ready")
        wait_ready(client, "http://java:8080/actuator/health")
        wait_ready(client, "http://jaeger:16686/api/services")
        # Only the fixture process uses FunctionModel; Java and both DBs are real.
        with patch("pulseflow_agent.agent.growth_investigator.create_model",
                   return_value=FunctionModel(offline)):
            thread = threading.Thread(target=server.run, daemon=True)
            thread.start()
            try:
                wait_ready(client, "http://127.0.0.1:8002/health/ready")
                tracer = telemetry.provider.get_tracer("acceptance")
                # Export actual caller roots too, so Jaeger has no missing remote parent.
                with tracer.start_as_current_span("acceptance.default-investigation") as root:
                    context = root.get_span_context()
                    default_id = f"{context.trace_id:032x}"
                    default_response = client.post(
                        "http://agent:8001/internal/v1/investigations",
                        headers={**headers, "traceparent":
                                 f"00-{default_id}-{context.span_id:016x}-01"},
                        json={"question": "最近活动转化率下降了，帮我分析原因。"},
                    )
                    assert default_response.status_code == 200, "running Agent investigation failed"
                    default_item = default_response.json()
                    assert default_item["final_diagnosis"]["status"] == "INSUFFICIENT_EVIDENCE"
                    assert not default_item["tool_trajectory"], \
                        "TestModel unexpectedly called tools"
                with tracer.start_as_current_span("acceptance.investigation") as root:
                    context = root.get_span_context()
                    trace_id = f"{context.trace_id:032x}"
                    response = client.post(
                        "http://127.0.0.1:8002/internal/v1/investigations",
                        headers={**headers, "traceparent":
                                 f"00-{trace_id}-{context.span_id:016x}-01"},
                        json={"question": "最近活动转化率下降了，帮我分析原因。"
                              "PRIVATE_PROMPT_SENTINEL"},
                    )
                assert response.status_code == 200, "fixture HTTP investigation failed"
                item = response.json()
                assert item["status"] in {"COMPLETED", "INSUFFICIENT_EVIDENCE"}, \
                    "fixture investigation did not finish"
                assert item["tool_trajectory"] == ["query_metric"], "business Tool not executed"
                assert item["evidence"] and item["evidence"][0]["trace_id"] == trace_id
                assert item["evidence"][0]["java_query_id"], "real Java evidence missing"

                # Safe exporter must also scrub a captured error and denied Java call.
                with httpx.Client(trust_env=False) as failing:
                    HTTPXClientInstrumentor.instrument_client(
                        failing, tracer_provider=telemetry.provider)
                    with telemetry.provider.get_tracer("acceptance").start_as_current_span(
                        "privacy.error-probe") as span:
                        error_id = f"{span.get_span_context().trace_id:032x}"
                        for key, value in zip((
                            "gen_ai.input.messages", "gen_ai.output.messages",
                            "gen_ai.tool.call.arguments", "gen_ai.tool.call.result",
                            "db.statement", "http.request.header.authorization",
                        ), SENTINELS[:6], strict=True):
                            span.set_attribute(key, value)
                        span.record_exception(RuntimeError("PRIVATE_EXCEPTION_SENTINEL"))
                        span.set_status(Status(StatusCode.ERROR, "PRIVATE_STATUS_SENTINEL"))
                        denied = failing.post(
                            "http://java:8080/internal/v1/agent-tools/metrics/query"
                            "?private=PRIVATE_URL_SENTINEL",
                            headers={"X-PulseFlow-Agent-Token": "PRIVATE_TOKEN_SENTINEL"},
                            json={},
                        )
                        assert denied.status_code == 401, "machine auth was not enforced"
                assert telemetry.provider.force_flush(timeout_millis=10000), "OTLP flush failed"
            finally:
                server.should_exit = True
                thread.join(timeout=20)
        trace = read_trace(client, trace_id, lambda t: any(
            service(t, s) == "pulseflow-java" and tags(s).get("db.system") == "mysql"
            for s in t["spans"]))
        check_chain(trace, trace_id)
        check_privacy(trace, token)
        default_trace = read_trace(client, default_id, lambda t: any(
            tags(s).get("gen_ai.operation.name") == "invoke_agent" for s in t["spans"]))
        check_privacy(default_trace, token)
        error_trace = read_trace(client, error_id, lambda t: any(
            service(t, s) == "pulseflow-java" for s in t["spans"]))
        check_privacy(error_trace, token)
        assert any(tags(s).get("error") is True for s in error_trace["spans"]), \
            "error status missing in Jaeger"
        assert any(tags(s).get("http.response.status_code", tags(s).get("http.status_code"))
                   == 401 for s in error_trace["spans"]), "denied request status missing"
        print(json.dumps({
            "status": "PASS", "mode": "offline FunctionModel; real HTTP, Java, MySQL and OTLP",
            "investigation_id": item["id"], "trace_id": trace_id,
            "default_testmodel_trace_id": default_id, "error_trace_id": error_id,
            "privacy": "PASS", "cross_service_parents": "PASS", "real_model": "NOT_RUN",
            "services": sorted({service(trace, s) for s in trace["spans"]}),
            "spans": [{"id": s["spanID"], "parent": parent(s),
                       "service": service(trace, s), "name": s["operationName"],
                       "duration_us": s["duration"]} for s in trace["spans"]],
        }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        # No raw exception text/stack or HTTP payload in acceptance output.
        print(json.dumps({"status": "FAIL", "error_type": type(error).__name__,
                          "check": str(error) if isinstance(error, AssertionError) else "runtime"}))
        raise SystemExit(1) from None
