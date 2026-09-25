"""FastAPI application with readiness checked at startup."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import ValidationError

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


def create_app(settings: AgentSettings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        try:
            configured = settings or AgentSettings()  # type: ignore[call-arg]
            client = httpx.AsyncClient(timeout=5.0)
            app.state.investigator = GrowthInvestigator(
                configured, AzurePiiGuardrail(configured, client)
            )
            app.state.ready = True
        except (ValidationError, ValueError):
            app.state.ready = False
            raise
        try:
            yield
        finally:
            await client.aclose()
            app.state.ready = False

    app = FastAPI(title="PulseFlow Agent", lifespan=lifespan)
    app.state.ready = False

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        if not app.state.ready:
            raise HTTPException(status_code=503, detail="agent not ready")
        return {"status": "ready"}

    return app


app = create_app()
