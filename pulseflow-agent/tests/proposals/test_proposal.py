"""Agent persists an evidence-backed proposal and never writes a Java business object."""

from typing import Any
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr
from pydantic_ai import ModelMessage, ModelResponse, ToolCallPart, ToolReturnPart
from pydantic_ai.exceptions import UnexpectedModelBehavior
from pydantic_ai.models.function import AgentInfo, FunctionModel
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.contracts import CampaignPerformanceArgs, PromotionFact, ToolMetadata
from pulseflow_agent.domain.investigation import Diagnosis, InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import OpenMedPiiGuardrail
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository
from pulseflow_agent.workspace.service import InvestigationService


def proposal(evidence_id: str) -> dict[str, Any]:
    return {
        "campaign_name": "Recall review",
        "objective": "RETENTION",
        "rationale": "Aggregate CTR supports a recall proposal",
        "target_audience": {"logic": "AND", "conditions": [
            {"field": "activeDays7d", "operator": "GTE", "value": 5, "value_type": "INTEGER"}
        ]},
        "channel": "PUSH",
        "schedule": {
            "type": "ONCE", "send_at": "2027-01-01T10:00:00+08:00", "timezone": "Asia/Shanghai"
        },
        "frequency_cap": {"max_times": 1, "window_hours": 24},
        "promotion_facts": [
            {"type": "COUPON", "threshold": "100", "discount": "10", "description": "满100减10"}
        ],
        "supporting_evidence_ids": [evidence_id],
    }


def settings(database_url: str) -> AgentSettings:
    return AgentSettings.model_validate({
        "pulseflow_agent_env": "test",
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("machine-secret"),
        "pulseflow_agent_database_url": SecretStr(database_url),
    })


async def seed(repo: SqlAlchemyInvestigationRepository) -> tuple[str, str]:
    created = await repo.create("Investigate recall")
    workspace = InvestigationWorkspace(goal=created.goal, id=created.id, repository=repo)
    evidence = await workspace.add_evidence(
        "get_campaign_performance",
        ToolMetadata.model_validate({
            "queryId": str(uuid4()), "generatedAt": "2026-09-26T00:00:00Z",
            "dataVersion": None, "source": "campaign-summary", "warnings": [],
        }),
        "CAMPAIGN=9 clickRate=0.1", CampaignPerformanceArgs(campaign_id=9),
    )
    await workspace.finish("COMPLETED", Diagnosis(
        status="DIAGNOSED", summary="Recall CTR is low", findings=[],
        evidence_ids=[evidence.id], unresolved_questions=[], confidence="medium",
        recommended_next_action=None,
    ))
    return created.id, evidence.id


class ProposalModel:
    def __init__(self, evidence_id: str) -> None:
        self.evidence_id = evidence_id

    async def respond(self, messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        tool_names = {tool.name for tool in info.function_tools}
        assert "create_campaign_draft" not in tool_names
        assert not tool_names.intersection(
            {"confirm_campaign", "activate_campaign", "send_campaign"}
        )
        results = [
            part for message in messages for part in message.parts
            if isinstance(part, ToolReturnPart)
        ]
        if not results:
            assert "persist_campaign_proposal" in tool_names
            return ModelResponse(parts=[ToolCallPart(
                "persist_campaign_proposal", {"proposal": proposal(self.evidence_id)}
            )])
        assert "persist_campaign_proposal" not in tool_names
        return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, {
            "status": "DIAGNOSED", "summary": "Proposal ready for review.",
            "findings": [], "evidence_ids": [self.evidence_id],
            "unresolved_questions": [], "confidence": "medium",
            "recommended_next_action": "Create a draft in PulseFlow Java",
        })])


@pytest.mark.asyncio
async def test_proposal_is_durable_and_java_write_is_absent(database_url: str) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    investigation_id, evidence_id = await seed(repo)
    config = settings(database_url)
    java_calls: list[str] = []

    def java(request: httpx.Request) -> httpx.Response:
        java_calls.append(request.url.path)
        return httpx.Response(500)

    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        guardrail = OpenMedPiiGuardrail(config)
        investigator = GrowthInvestigator(
            config, guardrail, PulseFlowApiClient(config, http),
            model=FunctionModel(ProposalModel(evidence_id).respond),
        )
        result = await InvestigationService(repo, investigator, guardrail).propose(
            investigation_id, "设计召回方案", 1024,
            [
                PromotionFact.model_validate(value)
                for value in proposal(evidence_id)["promotion_facts"]
            ],
        )
    assert len(result.proposals) == 1
    record = result.proposals[0]
    assert record.owner_id == 1024
    assert record.investigation_id == investigation_id
    assert record.status == "GENERATED" and record.draft_id is None
    assert "create_campaign_draft" not in result.tool_trajectory
    assert java_calls == []
    loaded = await repo.load_proposal(record.id)
    assert loaded.record.proposal.supporting_evidence_ids == [evidence_id]
    assert loaded.evidence_ids == [evidence_id]
    assert (await repo.mark_draft_created(record.id, 77)).draft_id == 77
    assert (await repo.mark_draft_created(record.id, 77)).draft_id == 77
    await engine.dispose()


@pytest.mark.asyncio
async def test_agent_cannot_invent_promotion_facts(database_url: str) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    investigation_id, evidence_id = await seed(repo)
    config = settings(database_url)
    async with httpx.AsyncClient() as http:
        guardrail = OpenMedPiiGuardrail(config)
        investigator = GrowthInvestigator(
            config, guardrail, PulseFlowApiClient(config, http),
            model=FunctionModel(ProposalModel(evidence_id).respond),
        )
        with pytest.raises(UnexpectedModelBehavior):
            await InvestigationService(repo, investigator, guardrail).propose(
                investigation_id, "设计召回方案", 1024, []
            )
    assert (await repo.load(investigation_id)).proposals == []
    await engine.dispose()
