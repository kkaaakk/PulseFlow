import asyncio
from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient, ToolClientError
from pulseflow_agent.domain.contracts import CampaignPerformanceArgs
from pulseflow_agent.main import create_app
from pulseflow_agent.runtime import AdmissionRejected, RunAdmission
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository
from pulseflow_agent.workspace.service import InvestigationService
from tests.test_foundation import settings


@pytest.mark.asyncio
async def test_admission_has_no_queue_and_rate_limit_is_bounded() -> None:
    limit = RunAdmission(
        settings(pulseflow_agent_max_concurrency=1, pulseflow_agent_runs_per_minute=2)
    )
    await limit.acquire()
    with pytest.raises(AdmissionRejected):
        await limit.acquire()
    limit.release("DONE", 0)
    await limit.acquire()
    limit.release("DONE", 0)
    with pytest.raises(AdmissionRejected):
        await limit.acquire()


@pytest.mark.asyncio
@pytest.mark.parametrize("timeout", [False, True])
async def test_background_cancel_timeout_and_shutdown_release_capacity(
    timeout: bool, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = settings(
        pulseflow_agent_max_concurrency=1,
        pulseflow_agent_run_timeout_seconds=0.1 if timeout else 30,
    )
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    repo = SqlAlchemyInvestigationRepository(engine)
    await repo.create_schema_for_tests()
    async with httpx.AsyncClient() as client:
        guard = AzurePiiGuardrail(config, client)
        investigator = GrowthInvestigator(config, guard, PulseFlowApiClient(config, client))
        entered = asyncio.Event()

        async def slow(*args: object, **kwargs: object) -> None:
            entered.set()
            await asyncio.sleep(30)

        monkeypatch.setattr(investigator._agent, "run", AsyncMock(side_effect=slow))
        service = InvestigationService(repo, investigator, guard)
        item = await service.start("Investigate conversion")
        await entered.wait()
        with pytest.raises(AdmissionRejected):
            await service.start("Another investigation")
        if timeout:
            await asyncio.sleep(0.3)
            # Overall timeout cancels model work, persisting the interrupted state.
            assert (await service.get(item.id)).status == "CANCELLED"
        else:
            assert (await service.cancel(item.id)).status == "CANCELLED"
        assert service.admission.active == 0
        restarted = await service.start("Resume investigation", item.id)
        assert restarted.id == item.id
        await service.shutdown()
        assert service.admission.active == 0
        assert not service._tasks
        with pytest.raises(AdmissionRejected):
            await service.start("after shutdown")
    await engine.dispose()


@pytest.mark.asyncio
async def test_restart_marks_running_investigations_recoverable() -> None:
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    repo = SqlAlchemyInvestigationRepository(engine)
    await repo.create_schema_for_tests()
    item = await repo.create("Interrupted question")
    await repo.recover_interrupted()
    assert (await repo.load(item.id)).status == "FAILED"
    assert (await repo.begin_followup(item.id)).status == "RUNNING"
    await engine.dispose()


@pytest.mark.asyncio
async def test_tool_circuit_breaker_stops_requests_and_never_retries_writes() -> None:
    calls = 0

    def failed(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(503, text="private upstream detail")

    async with httpx.AsyncClient(transport=httpx.MockTransport(failed)) as client:
        tools = PulseFlowApiClient(settings(), client)
        for _ in range(3):
            with pytest.raises(ToolClientError, match="java_tool_unavailable"):
                await tools.get_campaign_performance(
                    CampaignPerformanceArgs(campaign_id=1).campaign_id
                )
        with pytest.raises(ToolClientError, match="java_tool_circuit_open"):
            await tools.get_campaign_performance(1)
        assert calls == 3


def test_async_api_sse_auth_pii_and_readiness(monkeypatch: pytest.MonkeyPatch) -> None:
    app = create_app(settings())
    headers = {"X-PulseFlow-Agent-Token": "fake-internal-token"}
    with TestClient(app) as client:
        path = "/internal/v1/investigations"
        assert client.post(path + "/start", json={"question": "Growth"}).status_code == 401
        blocked = client.post(path + "/start", headers=headers, json={"question": "userId 123456"})
        assert blocked.status_code == 422
        assert "123456" not in blocked.text
        response = client.post(path + "/start", headers=headers, json={"question": "Growth"})
        assert response.status_code == 202
        id = response.json()["id"]
        assert client.get(f"{path}/{id}/events").status_code == 401
        events = client.get(f"{path}/{id}/events", headers=headers)
        assert "event: investigation_started" in events.text
        assert "event: diagnosis_ready" in events.text
        assert not any(
            secret in events.text
            for secret in ["system_prompt", "draft_grant", "reasoning", "fake-internal-token"]
        )
        assert (
            client.get(f"{path}/{id}", headers=headers).json()["status"] == "INSUFFICIENT_EVIDENCE"
        )
        monkeypatch.setattr(
            app.state.investigations._repository,
            "check_ready",
            AsyncMock(side_effect=RuntimeError("private password")),
        )
        ready = client.get("/health/ready")
        assert ready.status_code == 503
        assert "password" not in ready.text
