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

`PULSEFLOW_AGENT_MODEL=test` uses Pydantic AI's offline TestModel and returns an honest insufficient-evidence result without querying Java. The dynamic-path tests use FunctionModel with mocked Java responses. For an OpenAI-compatible provider, set `PULSEFLOW_AGENT_MODEL=openai:<model>`, `PULSEFLOW_AGENT_API_KEY`, optional `PULSEFLOW_AGENT_BASE_URL`, and preload the dedicated OpenMed Chinese PII model. Only the `GrowthInvestigator.run` wrapper invokes the model; it checks local business fields followed by local OpenMed PII in real-model mode. Java Tool observations are checked again before reaching the model.

For a local real-model run, prepare weights once with `uv run python -m pulseflow_agent.security.preload_openmed` after setting `PULSEFLOW_AGENT_MODEL` and `PULSEFLOW_AGENT_PII_CACHE_DIR`. Startup then loads those files in OpenMed `local_only` mode and fails before readiness if the model is missing or unusable. Compose handles preparation with `agent-pii-model-init` and stores weights in the persistent `pulseflow-openmed-cache` volume. The Agent keeps its read-only root filesystem and mounts that cache read-only. The default dedicated Chinese model is `OpenMed/OpenMed-PII-Chinese-BigMed-Large-560M-v1`; `lang="zh"` is fixed. The model identifier and cache directory are the only PII overrides. Inference stays in process and sends no text to OpenMed or Hugging Face services. Evaluate detection quality with synthetic data before using a new model or workload.

The Linux lockfile selects the official CPU-only PyTorch wheel to avoid bundling CUDA runtimes into the default Agent image. GPU deployment needs a separately qualified lockfile and image.

## Validation

```powershell
uv sync --locked
uv run ruff check .
uv run mypy src tests
uv run pytest
REAL_OPENMED_PII_EVAL=true uv run pytest tests/evals/test_openmed_chinese.py -k real_openmed -s
```

CI uses offline TestModel/FunctionModel, mocked Java responses and synthetic OpenMed detector results. It downloads no PII weights and makes no paid model calls. The installed Pydantic AI version enforces request, tool-call and input-token limits through `UsageLimits`; its API does not expose a cost limit. `PULSEFLOW_AGENT_MAX_COST_USD` remains reserved for a future supported implementation and is **not enforced**. The request/token limits bound runs now.

Phase 5 adds 22 fixture evaluation cases, an optional `REAL_AGENT_EVAL=true` suite, official OTel instrumentation and the machine-only `/internal/v1/agent-quality` metrics snapshot. See [evaluation and tracing](../docs/agent/agent-evaluation.md) for measurement limits and collector setup. Prompt, Tool content, credentials and SQL parameters are excluded from exported telemetry.

Phase 6 persists an evidence-backed CampaignProposal with Investigation and operator ownership. Agent tools stop at investigation and Proposal persistence. Java's authenticated `POST /api/campaign-proposals/{proposalId}/draft` reads the stored Proposal, validates ownership, Evidence, DSL and audience, then creates the Draft. The user confirms it separately. See [Proposal approval contract](../docs/agent/campaign-proposal-approval.md).
## Investigation UI and deployment

The Java-authenticated `/api/investigations` frontend now supports asynchronous investigation,
follow-up, cancellation, business SSE and human draft review. See
[production operations](../docs/agent/production-investigation.md) for the single-worker contract,
limits, Docker/Compose startup and recovery. Use `uv sync --locked` and migrate Alembic to head
before starting. Production requires a real model, a preloaded OpenMed cache and the dedicated Agent
MySQL schema; offline CI uses no paid provider requests.
