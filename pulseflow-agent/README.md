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

The [PII guardrail contract](../docs/agent/pii-guardrail-contract.md) defines the five-category
Azure policy, validated local phone/ID/payment-card detection, business-field preflight and strict
Tool values. Address-only free text is outside this promise; structured address fields remain
forbidden. Offline regression includes the final 100 saved synthetic Azure observations and
does not call Azure or a real LLM. Apply the locked dependencies and restart the Agent when
rolling out code; merging Git alone does not update a running process or replace `.env`.

```powershell
uv sync --locked
uv run ruff check .
uv run mypy src tests
uv run pytest
```

CI uses offline TestModel/FunctionModel and mocked Java/Azure responses. It makes no paid model or Azure calls. The installed Pydantic AI version enforces request, tool-call and input-token limits through `UsageLimits`; its API does not expose a cost limit. `PULSEFLOW_AGENT_MAX_COST_USD` remains reserved for a future supported implementation and is **not enforced**. The request/token limits bound runs now.

Phase 5 adds 22 fixture evaluation cases, an optional `REAL_AGENT_EVAL=true` suite, official OTel instrumentation and the machine-only `/internal/v1/agent-quality` metrics snapshot. See [evaluation and tracing](../docs/agent/agent-evaluation.md) for measurement limits and collector setup. Prompt, Tool content, credentials and SQL parameters are excluded from exported telemetry.

Phase 6 persists an evidence-backed CampaignProposal with Investigation and operator ownership. Agent tools stop at investigation and Proposal persistence. Java's authenticated `POST /api/campaign-proposals/{proposalId}/draft` reads the stored Proposal, validates ownership, Evidence, DSL and audience, then creates the Draft. The user confirms it separately. See [Proposal approval contract](../docs/agent/campaign-proposal-approval.md).
## Investigation UI and deployment

The Java-authenticated `/api/investigations` frontend now supports asynchronous investigation,
follow-up, cancellation, business SSE and human draft review. See
[production operations](../docs/agent/production-investigation.md) for the single-worker contract,
limits, Docker/Compose startup and recovery. Use `uv sync --locked` and migrate Alembic to head
before starting. Production requires a real model, Azure PII configuration and the dedicated Agent
MySQL schema; offline CI uses no paid provider requests.

## Business-aware investigation context

`workspace/context.py` organises run-entry history by question, Goal, Scope and validated
Tool selectors. It protects complete Hypothesis support/refutation and valid prior Diagnosis
references. Historical scopes remain `BACKGROUND`; the SDK carries new Tool results during
a run. The final JSON values and complete Query JSON still pass the existing PII guardrail.

`PULSEFLOW_AGENT_CONTEXT_TARGET_CHARS=12000` is a soft target for optional historical content,
measured in characters, not tokens. Short history is retained; duplicate conversation is
removed and long history is selected as complete business units. Required facts and reference
relationships can exceed the target without stopping the run. Builder does not count model
usage, reserve Tool tokens, or predict the whole request window.

Pydantic AI `UsageLimits` alone enforces each run's request/tool/cumulative input/output limits
(defaults 8/12/12000/4000). Usage comes from model responses; token limits may be detected after
a response, so they are not exact advance deductions. The installed Chat provider lacks a
reliable full-request preflight counter. Recognised provider context-window rejections finish
as `FAILED` with a content-free error code, separately from SDK `BUDGET_EXHAUSTED`; no unbounded
retry or second hard token budget is introduced.

The existing 22-case offline Eval suite and six context comparisons are retained. Set
`PULSEFLOW_CONTEXT_EVAL_REPORT` to save the comparison JSON. FunctionModel usage is synthetic;
fixture usage exercises SDK accounting and is not provider billing. See the
[implementation audit](../docs/agent/business-aware-context-audit.md) for the call chain,
capacity boundaries, multi-request tests and verification evidence.
