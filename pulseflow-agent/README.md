# PulseFlow Agent foundation

This Python 3.11 service has one Pydantic AI Growth Investigation Agent connected to Java's six read-only internal tools. Phase 4 persists investigations, Evidence, Hypotheses, visible messages and Diagnoses in a separate Agent schema. See the [architecture](../ARCHITECTURE.md) for tool and workspace boundaries. Java remains the authority for all business data and writes.

## Local setup

The normal runtime is the root `compose.yml`: run `.\start.ps1` from the repository root. It starts Java, Agent, dedicated MySQL schemas, Redis, Kafka, XXL-JOB and Jaeger together, imports existing local provider credentials, enables real-model mode and Azure PII, and exports traces. Offline CI remains isolated in `testing/docker-compose.test.yml`.

```powershell
cd pulseflow-agent
Copy-Item .env.example .env
uv sync --locked
uv run alembic upgrade head
uv run uvicorn pulseflow_agent.main:app --host 127.0.0.1 --port 8001
```

Set `PULSEFLOW_JAVA_BASE_URL`, `PULSEFLOW_AGENT_INTERNAL_TOKEN` and `PULSEFLOW_AGENT_DATABASE_URL` even in offline mode. `GET /health/live` reports the process; `GET /health/ready` checks configuration, Agent schema and initialized runtime. Startup fails when the schema has not been migrated. `POST /internal/v1/investigations` creates a persisted investigation; `GET /internal/v1/investigations/{id}` reads it; `POST /internal/v1/investigations/{id}/follow-up` continues it with an optional `scope`. All are machine-only endpoints using the internal Token header.

All business model calls use DeepSeek, defaulting to `PULSEFLOW_AGENT_MODEL=deepseek:deepseek-flash`. Configure `PULSEFLOW_AGENT_API_KEY`, optional `PULSEFLOW_AGENT_BASE_URL=https://api.deepseek.com`, plus Azure Language endpoint and key. The former `openai:deepseek-*` spelling is migrated automatically; other providers and endpoints are rejected. The factory uses Pydantic AI's DeepSeek provider, preserves `reasoning_content` during tool round trips, and uses automatic tool choice because thinking mode rejects forced tool choice. The managed HTTP client is shared with the model for tracing and shutdown.

Only the `GrowthInvestigator.run` wrapper invokes the model; it checks local business fields followed by Azure PII in real-model mode. Java Tool observations are checked again before reaching the model. Explicit `PULSEFLOW_AGENT_MODEL=test` uses the offline TestModel without querying Java; CI and ordinary tests select it explicitly.

For DeepSeek V4.1 Flash, set these values in the ignored `pulseflow-agent/.env` for local runs (or in deployment secrets for Compose):

```dotenv
PULSEFLOW_AGENT_MODEL=deepseek:deepseek-flash
PULSEFLOW_AGENT_BASE_URL=https://api.deepseek.com
PULSEFLOW_AGENT_API_KEY=<your DeepSeek API key>
AZURE_LANGUAGE_ENDPOINT=<your Azure Language endpoint>
AZURE_LANGUAGE_KEY=<your Azure Language key>
```

`deepseek-flash` is DeepSeek's API identifier for V4.1 Flash. Startup rejects real-model mode until Azure Language PII settings are present. Keep the API key out of tracked files. The offline `test` setting remains the default for CI.

## Validation

```powershell
uv sync --locked
uv run ruff check .
uv run mypy src tests
uv run pytest
```

CI uses offline TestModel/FunctionModel and mocked Java/Azure responses. It makes no paid model or Azure calls. The installed Pydantic AI version enforces request, tool-call and input-token limits through `UsageLimits`; its API does not expose a cost limit. `PULSEFLOW_AGENT_MAX_COST_USD` remains reserved for a future supported implementation and is **not enforced**. The request/token limits bound runs now.

Phase 5 adds 22 fixture evaluation cases, an optional `REAL_AGENT_EVAL=true` suite, official OTel instrumentation and the machine-only `/internal/v1/agent-quality` metrics snapshot. See [architecture](../ARCHITECTURE.md) for evaluation and tracing boundaries. Prompt, Tool content, credentials and SQL parameters are excluded from exported telemetry.

Phase 6 persists an evidence-backed CampaignProposal with Investigation and operator ownership. Agent tools stop at investigation and Proposal persistence. Java's authenticated `POST /api/campaign-proposals/{proposalId}/draft` reads the stored Proposal, validates ownership, Evidence, DSL and audience, then creates the Draft. The user confirms it separately. See [architecture](../ARCHITECTURE.md) for the human approval boundary.
## Investigation UI and deployment

The Java-authenticated `/api/investigations` frontend now supports asynchronous investigation,
follow-up, cancellation, business SSE and human draft review. See
[architecture](../ARCHITECTURE.md) for the single-worker contract and Docker/Compose boundary. Use `uv sync --locked` and migrate Alembic to head
before starting. Production requires a real model, Azure PII configuration and the dedicated Agent
MySQL schema; offline CI uses no paid provider requests.

## Business-aware investigation context

`workspace/context.py` selects run-entry history by question, goal, scope and validated Tool
selectors. Current Hypothesis support/refutation and valid prior Diagnosis references are
protected as complete units. Historical scopes remain `BACKGROUND`; the SDK carries new
Tool results during a run. The final rendered context still passes the Azure PII guardrail.

`PULSEFLOW_AGENT_CONTEXT_TOKENS=4000` uses a conservative UTF-8 byte text estimate, with
`PULSEFLOW_AGENT_CONTEXT_RESERVE_TOKENS=4000` for schemas, SDK history and subsequent turns.
`PULSEFLOW_AGENT_CONTEXT_WINDOW_TOKENS=32768` is an operational window, to be set at or below
the deployed model capacity. Available context also shrinks with cumulative Run usage;
existing SDK usage limits remain unchanged. Protected context that cannot fit ends as
`BUDGET_EXHAUSTED` with low confidence, without cutting IDs, values or references.

The existing 22-case offline Eval suite is joined by six before/after context scenarios.
Set `PULSEFLOW_CONTEXT_EVAL_REPORT` to save their JSON report. FunctionModel token usage is
synthetic and excludes instructions; it does not measure real-provider token savings.
See the [implementation audit](../docs/agent/business-aware-context-audit.md) for the call
chain, selection rules, estimate limits and validation evidence.
