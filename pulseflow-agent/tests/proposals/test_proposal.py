"""A PROPOSE capability can create one durable draft without any execution credential."""

import json
from typing import Any
from uuid import uuid4

import httpx
import pytest
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import SecretStr
from pydantic_ai import ModelMessage, ModelResponse, ToolCallPart, ToolReturnPart
from pydantic_ai.exceptions import UnexpectedModelBehavior
from pydantic_ai.models.function import AgentInfo, FunctionModel
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient, ToolClientError
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.campaign_proposal import CampaignDraftRequest, CampaignProposal
from pulseflow_agent.domain.contracts import CampaignPerformanceArgs, PromotionFact, ToolMetadata
from pulseflow_agent.domain.investigation import Diagnosis, InvestigationWorkspace
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository
from pulseflow_agent.workspace.service import InvestigationService

GRANT = "12345678-1234-1234-1234-123456789abc.PRIVATE_DRAFT_GRANT_SENTINEL"


def proposal(
    evidence_id: str, rationale: str = "Aggregate CTR supports a recall proposal"
) -> dict[str, Any]:
    return {
        "campaign_name": "Recall review",
        "objective": "RETENTION",
        "rationale": rationale,
        "target_audience": {
            "logic": "AND",
            "conditions": [
                {"field": "activeDays7d", "operator": "GTE", "value": 5, "value_type": "INTEGER"}
            ],
        },
        "channel": "PUSH",
        "schedule": {
            "type": "ONCE",
            "send_at": "2027-01-01T10:00:00+08:00",
            "timezone": "Asia/Shanghai",
        },
        "frequency_cap": {"max_times": 1, "window_hours": 24},
        "promotion_facts": [
            {"type": "COUPON", "threshold": "100", "discount": "10", "description": "满100减10"}
        ],
        "supporting_evidence_ids": [evidence_id],
    }


def settings(database_url: str) -> AgentSettings:
    return AgentSettings.model_validate(
        {
            "pulseflow_agent_env": "test",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_internal_token": SecretStr("machine-secret"),
            "pulseflow_agent_database_url": SecretStr(database_url),
        }
    )


async def seed(repo: SqlAlchemyInvestigationRepository) -> tuple[str, str]:
    created = await repo.create("Investigate recall")
    workspace = InvestigationWorkspace(goal=created.goal, id=created.id, repository=repo)
    evidence = await workspace.add_evidence(
        "get_campaign_performance",
        ToolMetadata.model_validate(
            {
                "queryId": str(uuid4()),
                "generatedAt": "2026-09-26T00:00:00Z",
                "dataVersion": None,
                "source": "campaign-summary",
                "warnings": [],
            }
        ),
        "CAMPAIGN=9 clickRate=0.1",
        CampaignPerformanceArgs(campaign_id=9),
    )
    await workspace.finish(
        "COMPLETED",
        Diagnosis(
            status="DIAGNOSED",
            summary="Recall CTR is low",
            findings=[],
            evidence_ids=[evidence.id],
            unresolved_questions=[],
            confidence="medium",
            recommended_next_action=None,
        ),
    )
    return created.id, evidence.id


class ProposalModel:
    def __init__(self, evidence_id: str, rationale: str = "Aggregate CTR supports recall") -> None:
        self.evidence_id = evidence_id
        self.rationale = rationale

    async def respond(self, messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        assert "PRIVATE_DRAFT_GRANT_SENTINEL" not in str(messages) + str(info.instructions)
        results = [
            part
            for message in messages
            for part in message.parts
            if isinstance(part, ToolReturnPart)
        ]
        if not results:
            definition = next(
                tool for tool in info.function_tools if tool.name == "create_campaign_draft"
            )
            assert "grant" not in json.dumps(definition.parameters_json_schema)
            assert not any(
                tool.name in {"confirm_campaign", "activate_campaign", "send_message"}
                for tool in info.function_tools
            )
            return ModelResponse(
                parts=[
                    ToolCallPart(
                        "create_campaign_draft",
                        {
                            "proposal": proposal(self.evidence_id, self.rationale),
                        },
                    )
                ]
            )
        assert "create_campaign_draft" not in {tool.name for tool in info.function_tools}
        return ModelResponse(
            parts=[
                ToolCallPart(
                    info.output_tools[0].name,
                    {
                        "status": "DIAGNOSED",
                        "summary": "Draft prepared; human confirmation is required.",
                        "findings": [],
                        "evidence_ids": [self.evidence_id],
                        "unresolved_questions": [],
                        "confidence": "medium",
                        "recommended_next_action": "Review and confirm in Java",
                    },
                )
            ]
        )


@pytest.mark.asyncio
async def test_proposal_uses_current_evidence_persists_and_never_executes(
    database_url: str,
) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    investigation_id, evidence_id = await seed(repo)
    config = settings(database_url)
    paths: list[str] = []
    exporter = InMemorySpanExporter()
    telemetry = Telemetry(exporter=exporter)

    def java(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        assert request.url.path == "/internal/v1/agent-tools/campaign-drafts"
        assert request.headers["X-PulseFlow-Draft-Grant"] == GRANT
        assert request.headers["X-PulseFlow-Agent-Token"] == "machine-secret"
        body = json.loads(request.content)
        assert body["investigationId"] == investigation_id
        assert body["proposal"]["supportingEvidenceIds"] == [evidence_id]
        assert "operatorId" not in body and "PRIVATE_DRAFT_GRANT" not in request.content.decode()
        return httpx.Response(
            200,
            json={
                "metadata": {
                    "queryId": str(uuid4()),
                    "generatedAt": "2026-09-26T00:00:00Z",
                    "dataVersion": "profile-v1",
                    "source": "campaign-draft",
                    "warnings": [],
                },
                "draftId": 77,
                "state": "DRAFT",
                "validationStatus": "VALIDATED",
                "estimatedCount": 42,
                "dataVersion": "profile-v1",
                "requiresHumanConfirmation": True,
                "approvalLevel": "PROPOSE",
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        HTTPXClientInstrumentor.instrument_client(http, tracer_provider=telemetry.provider)
        guardrail = AzurePiiGuardrail(config, http)
        investigator = GrowthInvestigator(
            config,
            guardrail,
            PulseFlowApiClient(config, http),
            model=FunctionModel(ProposalModel(evidence_id).respond),
            telemetry=telemetry,
        )
        result = await InvestigationService(repo, investigator, guardrail).propose(
            investigation_id,
            "设计满100减10召回草稿",
            SecretStr(GRANT),
            [
                PromotionFact.model_validate(value)
                for value in proposal(evidence_id)["promotion_facts"]
            ],
        )
    assert result.id == investigation_id
    assert len(result.evidence) == 1  # Draft result is not fabricated business Evidence
    assert len(result.proposals) == 1
    assert result.proposals[0].draft.draft_id == 77
    assert result.proposals[0].draft.requires_human_confirmation is True
    assert "PRIVATE_DRAFT_GRANT" not in result.model_dump_json()
    assert paths == ["/internal/v1/agent-tools/campaign-drafts"]
    exported = str([(span.attributes, span.events) for span in exporter.get_finished_spans()])
    assert "PRIVATE_DRAFT_GRANT_SENTINEL" not in exported
    assert "machine-secret" not in exported
    loaded = await repo.load(investigation_id)
    assert loaded.proposals[0].proposal.supporting_evidence_ids == [evidence_id]
    await engine.dispose()
    telemetry.shutdown()


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["unknown_evidence", "pii", "invented_offer"])
async def test_invalid_proposal_never_calls_java(database_url: str, mode: str) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    investigation_id, evidence_id = await seed(repo)
    config = settings(database_url)
    calls: list[str] = []

    def java(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        return httpx.Response(500)

    policy = ProposalModel(
        str(uuid4()) if mode == "unknown_evidence" else evidence_id,
        "分析 userId 123456" if mode == "pii" else "Current aggregate evidence",
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        guardrail = AzurePiiGuardrail(config, http)
        service = InvestigationService(
            repo,
            GrowthInvestigator(
                config,
                guardrail,
                PulseFlowApiClient(config, http),
                model=FunctionModel(policy.respond),
            ),
            guardrail,
        )
        error = PiiBlockedError if mode == "pii" else UnexpectedModelBehavior
        with pytest.raises(error):
            await service.propose(
                investigation_id,
                "设计召回草稿",
                SecretStr(GRANT),
                []
                if mode == "invented_offer"
                else [
                    PromotionFact.model_validate(value)
                    for value in proposal(evidence_id)["promotion_facts"]
                ],
            )
    assert calls == []
    assert (await repo.load(investigation_id)).proposals == []
    await engine.dispose()


@pytest.mark.asyncio
async def test_java_execution_result_is_not_accepted_as_a_draft(database_url: str) -> None:
    config = settings(database_url)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json={
                    "draftId": 77,
                    "state": "ACTIVE",
                    "campaignId": 99,
                },
            )
        )
    ) as http:
        client = PulseFlowApiClient(config, http)
        with pytest.raises(ToolClientError, match="invalid_java_tool_response"):
            await client.create_campaign_draft(
                CampaignDraftRequest(
                    investigation_id=str(uuid4()),
                    proposal=CampaignProposal.model_validate(proposal(str(uuid4()))),
                ),
                SecretStr(GRANT),
            )
        assert not hasattr(client, "confirm_campaign")
