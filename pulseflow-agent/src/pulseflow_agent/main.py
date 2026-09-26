"""FastAPI application with readiness checked at startup."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from hmac import compare_digest
from typing import Annotated

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
from pydantic import BaseModel, ConfigDict, Field, SecretStr, StringConstraints
from sqlalchemy.ext.asyncio import create_async_engine
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import JSONResponse, Response

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import Investigation
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.workspace.repository import (
    InvestigationConflictError,
    InvestigationNotFoundError,
    SqlAlchemyInvestigationRepository,
)
from pulseflow_agent.workspace.service import InvestigationService


class InvestigationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
    ]


class FollowUpRequest(InvestigationRequest):
    scope: Annotated[
        str | None, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ] = None


class ProposalRequest(InvestigationRequest):
    draft_grant: SecretStr = Field(min_length=40, max_length=512)
    promotion_facts: list[PromotionFact] = Field(default_factory=list, max_length=10)


def create_app(
    settings: AgentSettings | None = None, trace_runtime: Telemetry | None = None
) -> FastAPI:
    telemetry = trace_runtime or Telemetry()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        configured = settings or AgentSettings()  # type: ignore[call-arg]
        if (
            configured.pulseflow_agent_internal_token is None
            or not configured.pulseflow_agent_internal_token.get_secret_value()
        ):
            raise ValueError("Java internal token is required for investigation tools")
        database_url = configured.pulseflow_agent_database_url
        assert database_url is not None  # AgentSettings requires it
        try:
            engine = create_async_engine(database_url.get_secret_value(), pool_pre_ping=True)
        except Exception:
            raise ValueError("invalid Agent database configuration") from None
        app.state.settings = configured
        if configured.pulseflow_agent_otel_endpoint:
            telemetry.configure_endpoint(str(configured.pulseflow_agent_otel_endpoint))
        SQLAlchemyInstrumentor().instrument(
            engine=engine.sync_engine, tracer_provider=telemetry.provider
        )
        try:
            repository = SqlAlchemyInvestigationRepository(engine)
            try:
                if configured.pulseflow_agent_env == "test":
                    await repository.create_schema_for_tests()
                await repository.check_ready()
            except Exception:
                raise RuntimeError("Agent database unavailable or schema not migrated") from None
            async with httpx.AsyncClient(timeout=10.0) as client:
                HTTPXClientInstrumentor.instrument_client(
                    client, tracer_provider=telemetry.provider
                )
                guardrail = AzurePiiGuardrail(configured, client)
                investigator = GrowthInvestigator(
                    configured,
                    guardrail,
                    PulseFlowApiClient(configured, client, telemetry.metrics),
                    telemetry=telemetry,
                )
                app.state.investigations = InvestigationService(repository, investigator, guardrail)
                app.state.ready = True
                yield
        finally:
            app.state.ready = False
            await engine.dispose()
            SQLAlchemyInstrumentor().uninstrument(engine=engine.sync_engine)
            telemetry.shutdown()

    app = FastAPI(title="PulseFlow Agent", lifespan=lifespan)
    app.state.ready = False

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": "invalid_investigation_request"})

    @app.middleware("http")
    async def internal_auth(request: Request, call_next: RequestResponseEndpoint) -> Response:
        if not request.url.path.startswith("/internal/v1/"):
            return await call_next(request)
        configured: AgentSettings = request.app.state.settings
        expected = configured.pulseflow_agent_internal_token
        supplied = request.headers.get("X-PulseFlow-Agent-Token", "")
        if (
            expected is None
            or len(supplied) > 512
            or not compare_digest(supplied, expected.get_secret_value())
        ):
            return JSONResponse(status_code=401, content={"detail": "Unauthorized"})
        return await call_next(request)

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/internal/v1/agent-quality")
    async def agent_quality() -> dict[str, float | None]:
        return telemetry.metrics.snapshot()

    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        if not app.state.ready:
            raise HTTPException(status_code=503, detail="agent not ready")
        return {"status": "ready"}

    @app.post("/internal/v1/investigations")
    async def investigate(body: InvestigationRequest, request: Request) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.create(body.question)
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    @app.get("/internal/v1/investigations/{investigation_id}")
    async def get_investigation(investigation_id: str, request: Request) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.get(investigation_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    @app.post("/internal/v1/investigations/{investigation_id}/follow-up")
    async def follow_up(
        investigation_id: str, body: FollowUpRequest, request: Request
    ) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.follow_up(investigation_id, body.question, body.scope)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="investigation_busy") from None
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    @app.post("/internal/v1/investigations/{investigation_id}/proposal")
    async def propose(
        investigation_id: str, body: ProposalRequest, request: Request
    ) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.propose(
                investigation_id, body.question, body.draft_grant, body.promotion_facts
            )
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="proposal_not_available") from None
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    FastAPIInstrumentor.instrument_app(
        app,
        tracer_provider=telemetry.provider,
        excluded_urls="health/live,health/ready",
        http_capture_headers_server_request=[],
        http_capture_headers_server_response=[],
        http_capture_headers_sanitize_fields=[".*"],
        exclude_spans=["receive", "send"],
    )
    return app


app = create_app()
