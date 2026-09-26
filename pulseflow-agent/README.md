# PulseFlow Agent foundation

This Python 3.11 service has one Pydantic AI Growth Investigation Agent connected to Java's six [read-only internal tools](../docs/agent/agent-tool-contract.md). Phase 4 persists investigations, Evidence, Hypotheses, visible messages and Diagnoses in a separate Agent schema. See the [workspace contract](../docs/agent/agent-workspace-persistence.md). Java remains the authority for all business data and writes.

## Local setup

```powershell
cd pulseflow-agent
Copy-Item .env.example .env
uv sync --locked
uv run alembic upgrade head
uv run uvicorn pulseflow_agent.main:app --host 127.0.0.1 --port 8001
```

Set `PULSEFLOW_JAVA_BASE_URL`, `PULSEFLOW_AGENT_INTERNAL_TOKEN` and `PULSEFLOW_AGENT_DATABASE_URL` even in offline mode. `GET /health/live` reports the process; `GET /health/ready` checks configuration, Agent schema and initialized runtime. Startup fails when the schema has not been migrated. `POST /internal/v1/investigations` creates a persisted investigation; `GET /internal/v1/investigations/{id}` reads it; `POST /internal/v1/investigations/{id}/follow-up` continues it with an optional `scope`. All are machine-only endpoints using the internal Token header.

`PULSEFLOW_AGENT_MODEL=test` uses Pydantic AI's offline TestModel and returns an honest insufficient-evidence result without querying Java. The dynamic-path tests use FunctionModel with mocked Java responses. For an OpenAI-compatible provider, set `PULSEFLOW_AGENT_MODEL=openai:<model>`, `PULSEFLOW_AGENT_API_KEY`, optional `PULSEFLOW_AGENT_BASE_URL`, plus Azure Language endpoint and key. Only the `GrowthInvestigator.run` wrapper invokes the model; it checks local business fields followed by Azure PII in real-model mode. Java Tool observations are checked again before reaching the model.

## Validation

```powershell
uv sync --locked
uv run ruff check .
uv run mypy src tests
uv run pytest
```

CI uses offline TestModel/FunctionModel and mocked Java/Azure responses. It makes no paid model or Azure calls. The installed Pydantic AI version enforces request, tool-call and input-token limits through `UsageLimits`; its API does not expose a cost limit. `PULSEFLOW_AGENT_MAX_COST_USD` remains reserved for a future supported implementation and is **not enforced**. The request/token limits bound runs now.

Phase 5 adds 22 fixture evaluation cases, an optional `REAL_AGENT_EVAL=true` suite, official OTel instrumentation and the machine-only `/internal/v1/agent-quality` metrics snapshot. See [evaluation and tracing](../docs/agent/agent-evaluation.md) for measurement limits and collector setup. Prompt, Tool content, credentials and SQL parameters are excluded from exported telemetry.

Phase 6 adds a conditional `create_campaign_draft` Tool and persisted CampaignProposal. Use the Java authenticated Investigation/Proposal gateway to supply operator-owned, short-lived PROPOSE authorization; the Agent never gets a user login Token or execution Tool. After migrations, see [Proposal approval contract](../docs/agent/campaign-proposal-approval.md) for human review/confirmation and authorized promotion facts.
## Investigation UI and deployment

The Java-authenticated `/api/investigations` frontend now supports asynchronous investigation,
follow-up, cancellation, business SSE and human draft review. See
[production operations](../docs/agent/production-investigation.md) for the single-worker contract,
limits, Docker/Compose startup and recovery. Use `uv sync --locked` and migrate Alembic to head
before starting. Production requires a real model, Azure PII configuration and the dedicated Agent
MySQL schema; offline CI uses no paid provider requests.
