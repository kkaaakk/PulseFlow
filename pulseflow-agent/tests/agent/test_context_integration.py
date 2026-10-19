"""Exercise context through the real SDK loop, guardrail and repository/service."""

import json
from typing import Any

import httpx
import pytest
from pydantic_ai import ModelMessage, ModelResponse, ToolCallPart
from pydantic_ai.models.function import AgentInfo, FunctionModel
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.investigation import AgentMessage, Diagnosis, InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository
from pulseflow_agent.workspace.service import InvestigationService
from tests.evals.harness import CURRENT, contents, metadata
from tests.workspace.test_context import NOW, assessment, fact, hypothesis


def config(database_url: str = "sqlite+aiosqlite:///:memory:") -> AgentSettings:
    return AgentSettings.model_validate(
        {
            "pulseflow_agent_env": "test",
            "pulseflow_agent_model": "test",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_internal_token": "context-test-token",
            "pulseflow_agent_database_url": database_url,
        }
    )


def injected(info: AgentInfo) -> dict[str, Any]:
    assert info.instructions
    start = info.instructions.index('{"context_policy":')
    return json.JSONDecoder().raw_decode(info.instructions[start:])[0]  # type: ignore[no-any-return]


def finish(info: AgentInfo, ids: list[str]) -> ModelResponse:
    diagnosis = Diagnosis(
        status="DIAGNOSED" if ids else "INSUFFICIENT_EVIDENCE",
        summary="Aggregate facts reviewed" if ids else "Current scope needs evidence",
        findings=[],
        evidence_ids=ids,
        unresolved_questions=[] if ids else ["Need current data"],
        confidence="medium" if ids else "low",
        recommended_next_action=None,
    )
    return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, diagnosis.model_dump())])


async def test_followup_restores_all_records_and_injects_relevant_protected_history(
    database_url: str,
) -> None:
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    created = await repo.create("Review CTR")
    workspace = InvestigationWorkspace(goal=created.goal, id=created.id, repository=repo)
    records = [fact(0), fact(1), *[fact(i, "CONVERSIONS") for i in range(2, 25)]]
    for record in records:
        await repo.add_evidence(created.id, record)
    workspace.evidence = records
    await workspace.propose_hypothesis("A decline needs review", [records[0].id], [records[1].id])
    await workspace.add_message("USER", created.goal)
    await workspace.finish("COMPLETED", assessment([records[0].id]))
    await engine.dispose()

    # New engine, repository, service and investigator: no in-process memory reuse.
    restored_engine = create_async_engine(database_url)
    restored = SqlAlchemyInvestigationRepository(restored_engine)
    settings = config(database_url)
    model_calls = 0
    question = "Follow up on the CTR evidence"

    async def respond(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal model_calls
        model_calls += 1
        payload = injected(info)
        ids = {record["id"] for record in payload["relevant_evidence"]}
        assert {records[0].id, records[1].id} <= ids
        assert len(ids) < len(records)
        assert question not in json.dumps(payload["recent_conversation"])
        return finish(info, [records[0].id])

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: pytest.fail("A follow-up using restored evidence needs no Java call")
        )
    ) as http:
        guardrail = AzurePiiGuardrail(settings, http)
        service = InvestigationService(
            restored,
            GrowthInvestigator(
                settings,
                guardrail,
                PulseFlowApiClient(settings, http),
                model=FunctionModel(respond),
            ),
            guardrail,
        )
        result = await service.follow_up(created.id, question)
        separate = await restored.create("A separate investigation")
    assert result.id == created.id
    assert result.status == "COMPLETED"
    assert model_calls == 1
    assert [record.model_dump() for record in result.evidence] == [r.model_dump() for r in records]
    assert len(result.hypotheses) == 1
    assert [message.role for message in result.messages] == [
        "USER",
        "ASSISTANT",
        "USER",
        "ASSISTANT",
    ]
    assert separate.id != result.id and separate.evidence == []
    await restored_engine.dispose()


async def test_same_run_java_returns_only_travel_in_sdk_history_without_extra_model_calls() -> None:
    settings = config()
    model_calls = java_calls = 0

    async def respond(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal model_calls
        model_calls += 1
        payload = injected(info)
        items = [value for _, value in contents(messages)]
        assert payload["relevant_evidence"] == []
        for value in items:
            assert value["evidence_id"] not in (info.instructions or "")
            assert "0.123456789000" not in (info.instructions or "")
        if len(items) < 2:
            return ModelResponse(
                parts=[
                    ToolCallPart(
                        "query_metric",
                        {
                            "request": {
                                "metric": "CTR" if not items else "CONVERSION_RATE",
                                "time_range": CURRENT,
                            }
                        },
                    )
                ]
            )
        assert len({value["evidence_id"] for value in items}) == 2
        return finish(info, [value["evidence_id"] for value in items])

    def java(request: httpx.Request) -> httpx.Response:
        nonlocal java_calls
        java_calls += 1
        query = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "metadata": metadata(),
                "metric": query["metric"],
                "timeRange": CURRENT,
                "dimensions": [],
                "rows": [{"dimensions": {}, "value": "0.123456789000", "sampleSize": 100}],
                "sampleSize": 100,
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        result = await GrowthInvestigator(
            settings,
            AzurePiiGuardrail(settings, http),
            PulseFlowApiClient(settings, http),
            model=FunctionModel(respond),
        ).run("Review CTR")
    assert model_calls == 3 and java_calls == 2
    assert len(result.evidence) == 2
    assert "0.123456789000" in result.evidence[0].observation


async def test_scope_change_in_sdk_loop_reclassifies_snapshot_and_rejects_old_citations() -> None:
    record = fact(0)
    workspace = InvestigationWorkspace(
        goal="Review CTR", evidence=[record], hypotheses=[hypothesis([], [record.id])]
    )
    model_calls = 0

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def respond(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal model_calls
        model_calls += 1
        payload = injected(info)
        if model_calls == 1:
            assert payload["relevant_evidence"][0]["scope"] == "CURRENT"
            return ModelResponse(
                parts=[ToolCallPart("update_scope", {"description": "New campaign"})]
            )
        assert payload["investigation"]["scope_version"] == 1
        assert all(e["scope"] == "BACKGROUND" for e in payload["relevant_evidence"])
        assert all(h["scope"] == "BACKGROUND" for h in payload["hypotheses"])
        return finish(info, [record.id] if model_calls == 2 else [])

    settings = config()
    async with httpx.AsyncClient() as http:
        result = await GrowthInvestigator(
            settings,
            AzurePiiGuardrail(settings, http),
            PulseFlowApiClient(settings, http),
            model=FunctionModel(respond),
        ).run("Change the scope", workspace_factory=factory)
    assert model_calls == 3
    assert result.diagnosis.evidence_ids == []
    assert result.diagnosis.status == "INSUFFICIENT_EVIDENCE"
    assert result.diagnosis.confidence == "low"


@pytest.mark.parametrize(
    "field",
    [
        "observation",
        "query",
        "source",
        "data_version",
        "warnings",
        "hypothesis",
        "reason",
        "conversation",
        "diagnosis",
        "questions",
    ],
)
async def test_final_injected_context_is_checked_for_pii_in_every_text_field(field: str) -> None:
    record = fact(0)
    workspace = InvestigationWorkspace(goal="Review", evidence=[record])
    blocked = "userId 987654"
    if field == "query":
        record.query = {"legacy": blocked}
    elif field == "warnings":
        record.warnings = [blocked]
    elif field in {"hypothesis", "reason"}:
        item = hypothesis([], [record.id])
        item.statement = blocked if field == "hypothesis" else item.statement
        item.reason = blocked if field == "reason" else item.reason
        workspace.hypotheses.append(item)
    elif field in {"conversation", "diagnosis", "questions"}:
        diagnosis = assessment([record.id]) if field != "conversation" else None
        if diagnosis:
            diagnosis.summary = blocked if field == "diagnosis" else diagnosis.summary
            diagnosis.unresolved_questions = [blocked] if field == "questions" else []
        workspace.messages.append(
            AgentMessage(role="ASSISTANT", content=blocked, created_at=NOW, diagnosis=diagnosis)
        )
    else:
        setattr(record, field, blocked)

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def never(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        pytest.fail("PII in restored context must never reach a model")

    settings = config()
    async with httpx.AsyncClient() as http:
        with pytest.raises(PiiBlockedError):
            await GrowthInvestigator(
                settings,
                AzurePiiGuardrail(settings, http),
                PulseFlowApiClient(settings, http),
                model=FunctionModel(never),
            ).run("Continue", workspace_factory=factory)
    assert workspace.status == "FAILED"


async def test_azure_checks_the_complete_rendered_context_before_model_request() -> None:
    settings = AgentSettings.model_validate(
        {
            "pulseflow_agent_model": "openai:deepseek-flash",
            "pulseflow_agent_api_key": "fake-key",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_database_url": "sqlite+aiosqlite:///:memory:",
            "azure_language_endpoint": "https://azure.invalid",
            "azure_language_key": "fake-pii-key",
        }
    )
    record = fact(0).model_copy(update={"data_version": "azure-detected-sensitive-marker"})
    workspace = InvestigationWorkspace(goal="Review", evidence=[record])
    scanned: list[str] = []

    def azure(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["parameters"]["loggingOptOut"] is True
        text = body["analysisInput"]["documents"][0]["text"]
        scanned.append(text)
        return httpx.Response(
            200,
            json={
                "kind": "PiiEntityRecognitionResults",
                "results": {
                    "errors": [],
                    "documents": [
                        {
                            "id": "1",
                            "entities": [{"category": "Person"}]
                            if "azure-detected-sensitive-marker" in text
                            else [],
                        }
                    ],
                },
            },
        )

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def never(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        pytest.fail("Azure rejected this context")

    async with httpx.AsyncClient(transport=httpx.MockTransport(azure)) as http:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await GrowthInvestigator(
                settings,
                AzurePiiGuardrail(settings, http),
                PulseFlowApiClient(settings, http),
                model=FunctionModel(never),
            ).run("Continue", workspace_factory=factory)
    assert scanned[0] == "Continue"
    assert len(scanned) == 2
    assert json.loads(scanned[1])["relevant_evidence"][0]["data_version"] == record.data_version


async def test_oversize_protected_context_finishes_persisted_run_without_any_llm_call(
    database_url: str,
) -> None:
    settings = config(database_url)
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    created = await repo.create("Review")
    record = fact(0, observation="Complete business observation " * 500)
    await repo.add_evidence(created.id, record)
    workspace = InvestigationWorkspace(
        goal=created.goal, id=created.id, repository=repo, evidence=[record]
    )
    await workspace.propose_hypothesis("A tentative explanation", [record.id], [])
    await workspace.finish("COMPLETED", assessment([record.id]))

    async def never(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        pytest.fail("The required context does not fit; no model may be called")

    async with httpx.AsyncClient() as http:
        guardrail = AzurePiiGuardrail(settings, http)
        service = InvestigationService(
            repo,
            GrowthInvestigator(
                settings, guardrail, PulseFlowApiClient(settings, http), model=FunctionModel(never)
            ),
            guardrail,
        )
        result = await service.follow_up(created.id, "Continue")
    assert result.status == "BUDGET_EXHAUSTED"
    assert result.final_diagnosis and "context" in result.final_diagnosis.summary
    assert result.final_diagnosis.confidence == "low"
    assert result.evidence[0].model_dump() == record.model_dump()
    assert result.hypotheses[0].supporting_evidence_ids == [record.id]
    assert len(result.messages) == 3
    await engine.dispose()
