"""FastAPI application with readiness checked at startup."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from hmac import compare_digest
from typing import Annotated

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, StringConstraints
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import JSONResponse, Response

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.investigation import InvestigationResult
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError


class InvestigationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
    ]


def create_app(settings: AgentSettings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        configured = settings or AgentSettings()  # type: ignore[call-arg]
        if (configured.pulseflow_agent_internal_token is None
                or not configured.pulseflow_agent_internal_token.get_secret_value()):
            raise ValueError("Java internal token is required for investigation tools")
        app.state.settings = configured
        async with httpx.AsyncClient(timeout=10.0) as client:
            app.state.investigator = GrowthInvestigator(
                configured, AzurePiiGuardrail(configured, client),
                PulseFlowApiClient(configured, client),
            )
            app.state.ready = True
            try:
                yield
            finally:
                app.state.ready = False

    app = FastAPI(title="PulseFlow Agent", lifespan=lifespan)
    app.state.ready = False

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": "invalid_investigation_request"})

    @app.middleware("http")
    async def internal_auth(request: Request, call_next: RequestResponseEndpoint) -> Response:
        if request.url.path != "/internal/v1/investigations":
            return await call_next(request)
        configured: AgentSettings = request.app.state.settings
        expected = configured.pulseflow_agent_internal_token
        supplied = request.headers.get("X-PulseFlow-Agent-Token", "")
        if (expected is None or len(supplied) > 512
                or not compare_digest(supplied, expected.get_secret_value())):
            return JSONResponse(status_code=401, content={"detail": "Unauthorized"})
        return await call_next(request)

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        if not app.state.ready:
            raise HTTPException(status_code=503, detail="agent not ready")
        return {"status": "ready"}

    @app.post("/internal/v1/investigations")
    async def investigate(body: InvestigationRequest, request: Request) -> InvestigationResult:
        try:
            investigator: GrowthInvestigator = request.app.state.investigator
            return await investigator.run(body.question)
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    return app


app = create_app()
