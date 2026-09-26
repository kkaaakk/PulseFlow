"""Optional real MySQL validation, enabled by Agent CI service container."""

import os
from uuid import uuid4

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.domain.contracts import CampaignPerformanceArgs, ToolMetadata
from pulseflow_agent.domain.investigation import Diagnosis, InvestigationWorkspace
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository


@pytest.mark.asyncio
async def test_agent_schema_migrates_and_persists_on_mysql(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    url = os.getenv("AGENT_TEST_MYSQL_URL")
    if not url:
        pytest.skip("AGENT_TEST_MYSQL_URL is not configured")
    monkeypatch.setenv("PULSEFLOW_AGENT_DATABASE_URL", url)
    # Alembic's CLI runs its own event loop, so invoke it from a worker thread.
    import asyncio

    await asyncio.to_thread(command.upgrade, Config("alembic.ini"), "head")
    engine = create_async_engine(url, pool_pre_ping=True)
    try:
        repo = SqlAlchemyInvestigationRepository(engine)
        await repo.check_ready()
        created = await repo.create("MySQL schema smoke test")
        workspace = InvestigationWorkspace(goal=created.goal, id=created.id, repository=repo)
        await workspace.add_message("USER", created.goal)
        item = await workspace.add_evidence(
            "get_campaign_performance",
            ToolMetadata.model_validate({
                "queryId": str(uuid4()), "generatedAt": "2026-09-25T00:00:00Z",
                "dataVersion": "campaign-summary:v1", "source": "campaign-summary",
                "warnings": [],
            }),
            "CAMPAIGN=9 sent=100", CampaignPerformanceArgs(campaign_id=9),
        )
        proposed = await workspace.propose_hypothesis("Delivery count is available", [item.id], [])
        await workspace.update_hypothesis(
            proposed.id, "SUPPORTED", [item.id], [], "Java summary supports it", "medium"
        )
        diagnosis = Diagnosis(
            status="DIAGNOSED", summary="Java summary is available", findings=[],
            evidence_ids=[item.id], unresolved_questions=[], confidence="medium",
            recommended_next_action=None,
        )
        await workspace.finish("COMPLETED", diagnosis)
        loaded = await repo.load(created.id)
        assert loaded.id == created.id
        assert loaded.status == "COMPLETED"
        assert loaded.evidence[0].query == {"campaignId": 9}
        assert loaded.hypotheses[0].status == "SUPPORTED"
        assert loaded.messages[1].diagnosis is not None
        assert loaded.messages[1].diagnosis.evidence_ids == [item.id]
    finally:
        await engine.dispose()
