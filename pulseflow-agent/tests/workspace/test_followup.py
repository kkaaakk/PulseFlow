"""Workspace tools and a scope-changing follow-up reuse one persisted investigation."""

import json
from asyncio import CancelledError
from typing import Any
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr
from pydantic_ai import ModelMessage, ModelResponse, TextPart, ToolCallPart, ToolReturnPart
from pydantic_ai.models.function import AgentInfo, FunctionModel
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.investigation import (
    Hypothesis,
    InvestigationWorkspace,
    ToolObservation,
)
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository
from pulseflow_agent.workspace.service import InvestigationService

PERIOD = {"fromInclusive": "2026-09-01T00:00:00+08:00",
          "toExclusive": "2026-09-08T00:00:00+08:00"}


def settings(database_url: str) -> AgentSettings:
    return AgentSettings.model_validate({
        "pulseflow_agent_env": "test",
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("test-internal-token"),
        "pulseflow_agent_database_url": SecretStr(database_url),
    })


def returns(messages: list[ModelMessage]) -> list[Any]:
    contents: list[Any] = []
    for message in messages:
        for part in message.parts:
            if isinstance(part, ToolReturnPart):
                if isinstance(part.content, str):
                    try:
                        contents.append(json.loads(part.content))
                    except json.JSONDecodeError:
                        contents.append(part.content)
                else:
                    contents.append(part.content)
    return contents


def final(info: AgentInfo, body: dict[str, object]) -> ModelResponse:
    if info.output_tools:
        return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, body)])
    return ModelResponse(parts=[TextPart(json.dumps(body))])


async def first_model(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
    items = returns(messages)
    if len(items) == 0:
        return ModelResponse(parts=[ToolCallPart("query_metric", {"request": {
            "metric": "CTR", "time_range": PERIOD,
        }})])
    evidence_id = ToolObservation.model_validate(items[0]).evidence_id
    assert evidence_id is not None
    if len(items) == 1:
        return ModelResponse(parts=[ToolCallPart("propose_hypothesis", {
            "statement": "CTR declined", "supporting_evidence_ids": [evidence_id],
            "contradicting_evidence_ids": [], "reason": "Observed CTR",
        })])
    hypothesis_id = Hypothesis.model_validate(items[1]).id
    if len(items) == 2:
        return ModelResponse(parts=[ToolCallPart("update_hypothesis", {
            "hypothesis_id": hypothesis_id, "status": "SUPPORTED",
            "supporting_evidence_ids": [evidence_id],
            "contradicting_evidence_ids": [], "reason": "Java metric confirms it",
            "confidence": "medium",
        })])
    if len(items) == 3:
        return ModelResponse(parts=[ToolCallPart("list_hypotheses", {})])
    return final(info, {
        "status": "DIAGNOSED", "summary": "CTR declined.",
        "findings": [{"claim": "CTR declined", "evidence_ids": [evidence_id]}],
        "evidence_ids": [evidence_id], "unresolved_questions": [],
        "confidence": "medium", "recommended_next_action": None,
    })


async def followup_model(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
    items = returns(messages)
    if not items:
        assert info.instructions is not None
        assert "CTR declined" in info.instructions
        return ModelResponse(parts=[ToolCallPart("update_scope", {
            "description": "silent users for 30 days",
        })])
    return final(info, {
        "status": "INSUFFICIENT_EVIDENCE",
        "summary": "The Java metrics cannot filter this segment yet.",
        "findings": [], "evidence_ids": [],
        "unresolved_questions": ["Segmented conversion metrics are unavailable."],
        "confidence": "low", "recommended_next_action": None,
    })


@pytest.mark.asyncio
async def test_hypotheses_and_scope_followup_persist_without_fake_evidence(
    database_url: str,
) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    config = settings(database_url)
    java_calls = 0

    def java(_request: httpx.Request) -> httpx.Response:
        nonlocal java_calls
        java_calls += 1
        return httpx.Response(200, json={
            "metadata": {"queryId": str(uuid4()), "generatedAt": "2026-09-25T00:00:00Z",
                         "dataVersion": None, "source": "campaign-facts", "warnings": []},
            "metric": "CTR", "timeRange": PERIOD, "dimensions": [],
            "rows": [{"dimensions": {}, "value": "0.1000", "sampleSize": 100}],
            "sampleSize": 100,
        })

    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        guardrail = AzurePiiGuardrail(config, http)
        first = InvestigationService(repo, GrowthInvestigator(
            config, guardrail, PulseFlowApiClient(config, http), model=FunctionModel(first_model)
        ), guardrail)
        created = await first.create("Why did recall CTR fall?")
        assert created.status == "COMPLETED"
        assert len(created.evidence) == 1
        assert len(created.hypotheses) == 1
        assert created.hypotheses[0].status == "SUPPORTED"
        assert java_calls == 1
        assert created.tool_trajectory == [
            "query_metric", "propose_hypothesis", "update_hypothesis", "list_hypotheses"
        ]

        second = InvestigationService(repo, GrowthInvestigator(
            config, guardrail, PulseFlowApiClient(config, http), model=FunctionModel(followup_model)
        ), guardrail)
        resumed = await second.follow_up(created.id, "只看沉默30天以上用户。")
    assert resumed.id == created.id
    assert resumed.scope == "silent users for 30 days"
    assert resumed.scope_version == 1
    assert resumed.status == "INSUFFICIENT_EVIDENCE"
    assert resumed.final_diagnosis is not None
    assert resumed.final_diagnosis.evidence_ids == []
    assert resumed.evidence[0].id == created.evidence[0].id
    assert resumed.hypotheses[0].status == "REJECTED"
    assert resumed.hypotheses[0].reason == "superseded_by_scope_change"
    assert [item.role for item in resumed.messages] == [
        "USER", "ASSISTANT", "USER", "ASSISTANT"
    ]
    assert resumed.messages[1].diagnosis is not None
    assert resumed.messages[1].diagnosis.evidence_ids == [created.evidence[0].id]
    assert java_calls == 1  # follow-up did not invent a segmented Java metric
    await engine.dispose()


@pytest.mark.asyncio
async def test_cancelled_model_run_records_cancelled_status(database_url: str) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    config = settings(database_url)
    ids: list[str] = []

    async def factory(question: str) -> InvestigationWorkspace:
        created = await repo.create(question)
        ids.append(created.id)
        return InvestigationWorkspace(goal=question, id=created.id, repository=repo)

    async def cancel(_messages: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        raise CancelledError()

    async with httpx.AsyncClient() as http:
        investigator = GrowthInvestigator(
            config, AzurePiiGuardrail(config, http), PulseFlowApiClient(config, http),
            model=FunctionModel(cancel),
        )
        with pytest.raises(CancelledError):
            await investigator.run("Cancel this test investigation", workspace_factory=factory)
    loaded = await repo.load(ids[0])
    assert loaded.status == "CANCELLED"
    assert loaded.final_diagnosis is not None
    assert loaded.final_diagnosis.summary == "Investigation cancelled."
    await engine.dispose()
