"""Alembic-backed Agent state survives repository and engine recreation."""

from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.domain.contracts import QueryMetricArgs, ToolMetadata
from pulseflow_agent.domain.investigation import Diagnosis, InvestigationWorkspace
from pulseflow_agent.workspace.repository import (
    InvestigationConflictError,
    SqlAlchemyInvestigationRepository,
)


@pytest.mark.asyncio
async def test_evidence_hypotheses_messages_and_scope_survive_reload(database_url: str) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    original = await repo.create("Why did recall CTR fall?")
    workspace = InvestigationWorkspace(goal=original.goal, id=original.id, repository=repo)
    await workspace.add_message("USER", original.goal)
    await workspace.record_tool("query_metric")
    args = QueryMetricArgs.model_validate({
        "metric": "CTR", "timeRange": {
            "fromInclusive": "2026-09-01T00:00:00+08:00",
            "toExclusive": "2026-09-08T00:00:00+08:00",
        },
    })
    item = await workspace.add_evidence(
        "query_metric",
        ToolMetadata.model_validate({
            "queryId": str(uuid4()), "generatedAt": "2026-09-25T00:00:00Z",
            "dataVersion": None, "source": "campaign-facts", "warnings": [],
        }),
        "CTR rows=1: ALL value=0.1000 sampleSize=100", args,
    )
    hypothesis = await workspace.propose_hypothesis(
        "Click-through declined", [item.id], [], "Supported by CTR aggregate"
    )
    updated = await workspace.update_hypothesis(
        hypothesis.id, "SUPPORTED", [item.id], [], "Observed decline", "medium"
    )
    assert updated.status == "SUPPORTED"
    diagnosis = Diagnosis(
        status="DIAGNOSED", summary="CTR declined", findings=[], evidence_ids=[item.id],
        unresolved_questions=[], confidence="medium", recommended_next_action=None,
    )
    await workspace.finish("COMPLETED", diagnosis)
    await engine.dispose()

    second_engine = create_async_engine(database_url)
    second_repo = SqlAlchemyInvestigationRepository(second_engine)
    loaded = await second_repo.load(original.id)
    assert loaded.id == original.id
    assert loaded.status == "COMPLETED"
    assert loaded.final_diagnosis is not None
    assert loaded.final_diagnosis.evidence_ids == [item.id]
    assert loaded.evidence[0].java_query_id == item.java_query_id
    assert loaded.hypotheses[0].status == "SUPPORTED"
    assert [message.role for message in loaded.messages] == ["USER", "ASSISTANT"]
    assert loaded.messages[1].diagnosis is not None
    assert loaded.messages[1].diagnosis.evidence_ids == [item.id]
    assert loaded.tool_trajectory == ["query_metric"]

    resumed = await second_repo.begin_followup(original.id)
    assert resumed.status == "RUNNING"
    with pytest.raises(InvestigationConflictError):
        await second_repo.begin_followup(original.id)
    followup = InvestigationWorkspace(
        goal=resumed.goal, id=resumed.id, scope=resumed.scope,
        scope_version=resumed.scope_version, repository=second_repo,
        evidence=list(resumed.evidence), hypotheses=list(resumed.hypotheses),
    )
    await followup.change_scope("silent users for 30 days")
    assert followup.scope_version == 1
    assert followup.evidence_ids == set()
    assert followup.evidence[0].id == item.id  # kept as historical background
    assert followup.hypotheses[0].status == "REJECTED"
    reloaded = await second_repo.load(original.id)
    assert reloaded.scope == "silent users for 30 days"
    assert reloaded.evidence[0].id == item.id
    assert reloaded.hypotheses[0].reason == "superseded_by_scope_change"
    await second_engine.dispose()


def test_production_settings_require_dedicated_agent_schema() -> None:
    from pydantic import SecretStr, ValidationError

    from pulseflow_agent.config import AgentSettings

    with pytest.raises(ValidationError, match="dedicated Agent MySQL"):
        AgentSettings.model_validate({
            "pulseflow_agent_env": "production",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_database_url": SecretStr("mysql+asyncmy://root:secret@db/pulseflow"),
            "pulseflow_agent_model": "openai:sample-model",
            "pulseflow_agent_api_key": SecretStr("fake-model-key"),
            "pulseflow_agent_internal_token": SecretStr("fake-internal-token"),
            "azure_language_endpoint": "https://example.cognitiveservices.azure.com",
            "azure_language_key": SecretStr("fake-azure-key"),
        })
