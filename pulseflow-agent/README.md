# PulseFlow Agent foundation

This is the Phase 1 Python 3.11 service foundation. It has one typed Pydantic AI agent and no connected business tools or investigation API yet. Java now exposes the Phase 2 [read-only internal tool contract](../docs/agent/agent-tool-contract.md); Python integration follows in Phase 3. Java remains the authority for all business data and writes.

## Local setup

```powershell
cd pulseflow-agent
Copy-Item .env.example .env
uv sync --locked
uv run uvicorn pulseflow_agent.main:app --host 127.0.0.1 --port 8001
```

Set `PULSEFLOW_JAVA_BASE_URL` even in offline mode. `GET /health/live` reports the process; `GET /health/ready` reports validated configuration and initialized runtime. Startup fails when required settings are missing.

`PULSEFLOW_AGENT_MODEL=test` uses Pydantic AI's offline TestModel. For an OpenAI-compatible provider, set `PULSEFLOW_AGENT_MODEL=openai:<model>`, `PULSEFLOW_AGENT_API_KEY`, optional `PULSEFLOW_AGENT_BASE_URL`, plus Azure Language endpoint and key. Production additionally requires `PULSEFLOW_AGENT_INTERNAL_TOKEN`. Only the `GrowthInvestigator.run` wrapper invokes the model, and it checks local business fields followed by Azure PII in real-model mode. The service currently exposes health routes only.

## Validation

```powershell
uv sync --locked
uv run ruff check .
uv run mypy src tests
uv run pytest
```

CI uses the offline test model and mocked Azure responses. It makes no paid model or Azure calls. The installed Pydantic AI version enforces request, tool-call and input-token limits through `UsageLimits`; its API does not expose a cost limit. `PULSEFLOW_AGENT_MAX_COST_USD` is reserved for a future supported implementation and is **not enforced** in Phase 1. The request/token limits bound runs now.
