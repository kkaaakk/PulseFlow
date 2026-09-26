# Phase 7: Investigation UI and production operations

## User workflow

Open **Investigations** after Java login. Start a question, then use the persistent investigation URL to
return to its visible conversation, scope, tool activity, Evidence, Hypotheses and Diagnosis. Follow-ups
keep the same investigation ID. Changing scope makes older evidence historical background. A running
investigation can be cancelled and later resumed.

An evidence-supported completed Diagnosis enables **生成 Campaign Proposal**. The operator provides
only approved promotion facts. Java validates the DSL and previews the audience, then saves a Draft.
The review panel shows audience conditions, channel, schedule, frequency cap, facts, warnings and data
version. **确认创建 Campaign** opens a separate confirmation dialog. Cancelling that dialog performs
no confirm request. Confirming uses the existing Java user-authenticated draft endpoint and creates a
Campaign in **DRAFT** status; it does not activate or deliver it.

Demo mode is visibly labelled and returns insufficient evidence, with no invented business data.

## Public API and isolation

Browser → Java Sa-Token gateway → Agent → Java internal tools.

| Public Java route | Behavior |
| --- | --- |
| `POST /api/investigations` | Start asynchronous investigation; Java registers session ownership |
| `GET /api/investigations/{id}` | Read owned visible workspace |
| `POST /api/investigations/{id}/follow-up` | Resume owned investigation, optional scope change |
| `POST /api/investigations/{id}/cancel` | Cancel owned active run |
| `GET /api/investigations/{id}/events` | Owned SSE business events |
| `POST /api/investigations/{id}/proposal` | Create a Java-owned draft with a short-lived PROPOSE grant |
| `/api/campaign-drafts/{id}` and existing confirm/preview routes | Authoritative human review |

Every ID-based gateway route checks Java ownership **before** contacting Agent. Browser-supplied
operator IDs are never used for ownership. Machine secrets and draft grants remain server-side.
The public projection omits model messages, system prompts and hidden reasoning. SSE carries only
business IDs, tool names and status, never raw model tokens or reasoning.

The event names are `investigation_started`, `tool_started`, `tool_completed`, `evidence_added`,
`hypothesis_changed`, `diagnosis_ready`, `error`. Events are reconstructed from persisted workspace
changes with a one-second polling interval. A completed Java observation produces `tool_completed`.
They are progress hints; the owned GET is the authoritative snapshot. Reconnection may replay hints,
so clients refresh snapshots instead of appending duplicate evidence. There is no token stream.
The browser sends the Java login header through `fetch`, closes streams on navigation, and offers
manual refresh after disconnection. A stream is bounded to 110 seconds, and Java to 115 seconds.

## Runtime limits

| Limit | Default |
| --- | --- |
| Agent active runs / new runs per minute | 4 / 20, no unbounded waiting queue |
| Java operator submissions per minute | 6, tracked for at most 1,024 active operators |
| Java concurrent submission requests / SSE workers | 4 / 8, no SSE queue |
| Agent concurrent SSE connections | 16 |
| Whole run deadline | 90 seconds, configurable up to 110 |
| Model request timeout / max output per request | 20 seconds / 2,000 tokens |
| Run model requests / tool calls / input tokens / output tokens | 8 / 12 / 12,000 / 4,000 |
| Java tool request | 10 seconds, no transport retries |
| Agent HTTP connections / keepalive | 16 / 8, connect and pool waits 3 seconds |
| Agent MySQL pool | 5, no overflow, pool wait 5 seconds |
| Java tool circuit breaker | Opens after 3 unavailable calls for 15 seconds |

Provider SDK transport retries are disabled so wire retries cannot silently multiply model usage.
PydanticAI output/tool argument correction remains bounded by the existing retry and UsageLimits
policy. Draft writes are not automatically retried. Unknown provider pricing remains `null`; the
legacy `MAX_COST_USD` setting is advisory, **not a hard billing cap**. Token/request limits and the
overall deadline are the enforced usage controls. Real provider pricing and billing alerts need
deployment-specific configuration.

Runtime logs are JSON with event, terminal outcome and duration only. Prompts, observations, tokens,
headers and raw exception text are excluded. Existing safe OTel export remains in place. Readiness
checks Agent DB connectivity and required tables; liveness is independent. Failures are returned as
fixed public codes. One unavailable Agent does not prevent ordinary Java campaign APIs from starting.

## Process lifecycle and recovery

Run **one Uvicorn worker and one Agent replica per Agent schema**. Admission and cancellation live
in that process; distributed leases and a durable queue are not implemented. On startup, persisted
RUNNING investigations are marked FAILED and can be resumed. Do not attach a second active replica
to the same schema, because startup recovery assumes exclusive process ownership.

Graceful shutdown stops admission and cancels active background runs, persisting interrupted state
before closing HTTP/DB resources. Uvicorn gets 15 seconds; Compose allows 30. Abrupt kill is recovered
by the next startup. In-memory SQLite serializes its shared connection; MySQL uses bounded pooling.

Expired Java grant rows older than 24 hours are removed hourly. Drafts and ownership records are
retained. If Java saved a draft but the Agent response/persistence fails, the gateway tries to recover
that same owned draft before returning an error. A new explicit proposal request may still create a
second draft. This is not a distributed transaction. Review existing drafts before resubmitting after
an ambiguous failure; database backup/retention and reconciliation policies remain operator duties.

## Development Compose

The existing infrastructure Compose gains an opt-in `agent` profile. Set a dedicated machine token:

```powershell
$env:PULSEFLOW_AGENT_INTERNAL_TOKEN = '<dedicated local machine secret>'
docker compose -f testing/docker-compose.test.yml --profile agent up --build -d
```

The stack builds Java and Agent, initializes a separate Agent MySQL server/user/schema, runs Alembic
to head, and starts Agent. Java Flyway applies business migrations. The Agent receives **no business
database credentials**, publishes no host port, and reaches Java on `agent-internal` (`internal: true`).
The separate `agent-egress` network allows provider/Azure calls when explicitly configured; Java is
not attached to that network. Default model `test` makes no real provider calls.

Agent image uses Python 3.11 slim, a versioned uv builder, `uv sync --locked --no-dev`, TLS root
certificates, UID 10001, readiness healthcheck, exec-form Uvicorn and SIGTERM shutdown. Runtime secrets
are supplied through environment/secret management, not baked into the image. The service filesystem
is read-only with `/tmp` tmpfs, dropped capabilities and no-new-privileges.

For production, use `PULSEFLOW_AGENT_ENV=production`, an approved real model, provider/Azure secrets,
and a dedicated `mysql+asyncmy` Agent user/schema. Supply secrets through the deployment manager;
do not check in `.env`. Restrict public ingress to the Java authenticated routes, deny `/internal/**`,
terminate TLS, apply provider egress restrictions, rotate machine tokens and configure backups/OTel.
This Compose file's passwords and local auth directory are development fixtures, not production
credentials. Check and roll forward schema migrations before starting the service.

## Verification

Ordinary CI runs offline model/PII fixtures. Python tests cover admission, cancellation, deadline,
shutdown, startup recovery, readiness and circuit breaking. Java tests cover ownership on read,
follow-up, cancel, proposal and SSE; bounded admission; public/SSE field filtering. Vue tests cover
evidence references, scope continuity, proposal gating and cancellation/acceptance of human approval.

The `agent-compose` CI job builds both images and runs the actual offline Java login → Agent DB →
owned read/follow-up/SSE path, checks non-root health, rejects another operator and PII, then cleans
up its isolated test volumes. No real LLM or paid Azure calls are made. Real model quality/latency,
deployment credentials and production traffic are not certified by these offline checks.

The Compose gate also caught a pre-existing broad MyBatis scan that registered business service
interfaces as database mappers. Application scanning now requires `@Mapper`; a registration probe
uses the actual application configuration to verify real mapper beans and exclude the preview
service interface before opening external resources.

References: [PydanticAI retries](https://github.com/pydantic/pydantic-ai/blob/main/docs/retries.md),
[UsageLimits](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/),
[Compose services](https://docs.docker.com/reference/compose-file/services/),
[Compose networks](https://docs.docker.com/reference/compose-file/networks/).
