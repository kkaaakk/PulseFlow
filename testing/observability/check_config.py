"""No Docker daemon required: verify rendered profiles, ports and isolation."""

import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "testing/docker-compose.test.yml"
OVERRIDE = ROOT / "testing/docker-compose.observability.yml"


# 调用 Docker Compose 配置解析命令并返回结果，供静态检查使用。
def config(*args):
    result = subprocess.run(
        ["docker", "compose", "-f", str(BASE), *args, "config", "--format", "json"],
        env={**os.environ, "PULSEFLOW_AGENT_INTERNAL_TOKEN": "config-check-placeholder",
             "COMPOSE_PROFILES": ""},
        check=True, capture_output=True, text=True,
    )
    return json.loads(result.stdout)


# 验证观测测试栈的 Compose 配置、服务和必要约束，不启动业务负载。
def run():
    base = config("--profile", "agent")
    default = config("-f", str(OVERRIDE))
    enabled = config("-f", str(OVERRIDE), "--profile", "agent", "--profile", "observability")
    assert "jaeger" not in base["services"] and "jaeger" not in default["services"]
    assert "trace-check" not in enabled["services"], "fixture must be explicitly invoked"
    assert "PULSEFLOW_AGENT_OTEL_ENDPOINT" not in base["services"]["agent"]["environment"]
    assert "PULSEFLOW_OTEL_DISABLED" not in base["services"]["java"]["environment"]
    jaeger = enabled["services"]["jaeger"]
    assert jaeger["image"] == "cr.jaegertracing.io/jaegertracing/jaeger:2.17.0"
    assert len(jaeger["ports"]) == 1
    assert jaeger["ports"][0]["host_ip"] == "127.0.0.1"
    assert jaeger["ports"][0]["target"] == 16686
    assert str(jaeger["ports"][0]["published"]) == "16686"
    assert set(jaeger["networks"]) == {"agent-internal", "observability-ui"}
    assert all(enabled["networks"][key] == value for key, value in base["networks"].items())
    assert not enabled["networks"]["observability-ui"].get("internal", False)
    assert all("observability-ui" not in s["networks"] for name, s in
               enabled["services"].items() if name != "jaeger")
    assert enabled["volumes"] == base["volumes"]
    for name in ("mysql", "agent-mysql", "agent-migrate", "redis", "kafka"):
        assert enabled["services"][name] == base["services"][name], name
    for name in ("agent", "java"):
        original = base["services"][name]
        observed = enabled["services"][name]
        assert original["networks"] == observed["networks"], name
        assert original.get("ports") == observed.get("ports"), name
        assert original.get("security_opt") == observed.get("security_opt"), name
        assert original.get("depends_on") == observed.get("depends_on"), name
    assert enabled["networks"]["agent-internal"]["internal"] is True
    agent_env = enabled["services"]["agent"]["environment"]
    java_env = enabled["services"]["java"]["environment"]
    assert agent_env["PULSEFLOW_AGENT_OTEL_ENDPOINT"] == "http://jaeger:4318"
    assert java_env["OTEL_EXPORTER_OTLP_ENDPOINT"] == "http://jaeger:4318"
    assert java_env["OTEL_EXPORTER_OTLP_PROTOCOL"] == "http/protobuf"
    assert java_env["PULSEFLOW_OTEL_DISABLED"] == "false"
    assert java_env["OTEL_TRACES_EXPORTER"] == "otlp"
    print("PASS: opt-in profiles, loopback UI, OTLP endpoints, unchanged networks/DB/defaults")


if __name__ == "__main__":
    run()
