"""Exercise context through the real SDK loop, guardrail and repository/service."""

import json
from typing import Any

import httpx
import pytest
from pydantic_ai import ModelMessage, ModelResponse, RunContext, ToolCallPart
from pydantic_ai.models.function import AgentInfo, FunctionModel
from pydantic_ai.usage import RequestUsage
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.agent.dependencies import AgentDependencies, ProposalContext
from pulseflow_agent.agent.growth_investigator import GrowthInvestigator, create_usage_limits
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import AgentMessage, Diagnosis, InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.workspace.repository import SqlAlchemyInvestigationRepository
from pulseflow_agent.workspace.service import InvestigationService
from tests.evals.harness import BASELINE, CURRENT, EvalCase, FixtureServer, contents, metadata
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
    checked: list[Any] = []

    class InspectingGuardrail(AzurePiiGuardrail):
        async def check(self, content: Any) -> None:
            checked.append(content)
            await super().check(content)

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
                            "entities": [
                                {
                                    "category": "Person",
                                    "offset": 0,
                                    "length": len(text),
                                    "confidenceScore": 0.99,
                                }
                            ]
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
                InspectingGuardrail(settings, http),
                PulseFlowApiClient(settings, http),
                model=FunctionModel(never),
            ).run("Continue", workspace_factory=factory)
    assert scanned[0] == "Continue"
    assert record.data_version in scanned
    final_payload = checked[1][0]
    assert final_payload["relevant_evidence"][0]["data_version"] == record.data_version
    assert json.loads(checked[1][1][0]) == record.query


async def test_oversize_protected_context_continues_and_preserves_persisted_records(
    database_url: str,
) -> None:
    settings = config(database_url).model_copy(
        update={"pulseflow_agent_context_target_chars": 1000}
    )
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

    model_calls = 0

    async def respond(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal model_calls
        model_calls += 1
        payload = injected(info)
        assert len(json.dumps(payload)) > 1000
        assert payload["relevant_evidence"][0]["observation"] == record.observation
        return finish(info, [record.id])

    async with httpx.AsyncClient() as http:
        guardrail = AzurePiiGuardrail(settings, http)
        service = InvestigationService(
            repo,
            GrowthInvestigator(
                settings,
                guardrail,
                PulseFlowApiClient(settings, http),
                model=FunctionModel(respond),
            ),
            guardrail,
        )
        result = await service.follow_up(created.id, "Continue")
    assert result.status == "COMPLETED"
    assert model_calls == 1
    assert result.final_diagnosis and result.final_diagnosis.evidence_ids == [record.id]
    assert result.evidence[0].model_dump() == record.model_dump()
    assert result.hypotheses[0].supporting_evidence_ids == [record.id]
    assert len(result.messages) == 3
    await engine.dispose()


async def test_untrusted_legacy_query_keys_are_checked_before_the_model() -> None:
    record = fact(0).model_copy(update={"query": {"userId": None}})
    workspace = InvestigationWorkspace(goal="Review", evidence=[record])

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def never(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        pytest.fail("PII keys cannot hide in a legacy query")

    settings = config()
    async with httpx.AsyncClient() as http:
        with pytest.raises(PiiBlockedError, match="blocked_business_field"):
            await GrowthInvestigator(
                settings,
                AzurePiiGuardrail(settings, http),
                PulseFlowApiClient(settings, http),
                model=FunctionModel(never),
            ).run("Continue", workspace_factory=factory)


@pytest.mark.parametrize("blocked", [False, True])
async def test_authorized_facts_share_the_checked_json_without_losing_precision(
    blocked: bool,
) -> None:
    record = fact(0)
    workspace = InvestigationWorkspace(goal="Review", id="context-test", evidence=[record])
    promotion = PromotionFact(
        type="COUPON",
        discount="10.0000",  # type: ignore[arg-type]
        description="userId 987654" if blocked else "Authorized operator text",
    )
    authorization = ProposalContext("context-test", 1, [promotion])
    model_calls = 0

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def respond(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal model_calls
        model_calls += 1
        assert not blocked
        payload = injected(info)
        assert payload["operator_authorized_promotion_facts"] == [
            promotion.model_dump(mode="json", by_alias=True)
        ]
        assert payload["operator_authorized_promotion_facts"][0]["discount"] == "10.0000"
        return finish(info, [record.id])

    settings = config()
    async with httpx.AsyncClient() as http:
        investigator = GrowthInvestigator(
            settings,
            AzurePiiGuardrail(settings, http),
            PulseFlowApiClient(settings, http),
            model=FunctionModel(respond),
        )
        if blocked:
            with pytest.raises(PiiBlockedError, match="blocked_business_field"):
                await investigator.run(
                    "Continue", workspace_factory=factory, proposal_context=authorization
                )
        else:
            await investigator.run(
                "Continue", workspace_factory=factory, proposal_context=authorization
            )
    assert model_calls == (0 if blocked else 1)


async def test_sensitive_query_key_text_is_checked_by_existing_real_mode_guardrail() -> None:
    from tests.test_foundation import azure_response
    from tests.test_foundation import settings as real_settings

    record = fact(0).model_copy(update={"query": {"联系电话：13800138000": None}})
    workspace = InvestigationWorkspace(goal="Review", evidence=[record])

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def never(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        pytest.fail("An untrusted JSON key reached the model")

    settings = real_settings(real=True)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await GrowthInvestigator(
                settings,
                AzurePiiGuardrail(settings, http),
                PulseFlowApiClient(settings, http),
                model=FunctionModel(never),
            ).run("Continue", workspace_factory=factory)


async def multi_request_investigation(steps: int = 7) -> dict[str, Any]:
    """A fixture policy; explicit usage exercises the SDK, not a real tokenizer."""
    from pathlib import Path

    case = EvalCase.model_validate(
        json.loads((Path(__file__).parents[1] / "evals/cases.json").read_text(encoding="utf-8"))[0]
    )
    server = FixtureServer(case)
    historical = [fact(60), fact(61)]
    workspace = InvestigationWorkspace(
        goal="Review CTR",
        evidence=list(historical),
        hypotheses=[hypothesis([historical[0].id], [historical[1].id])],
        messages=[
            AgentMessage(
                role="ASSISTANT",
                content="Prior finding",
                created_at=NOW,
                diagnosis=assessment([historical[0].id]),
            ),
        ],
    )
    canonical = [
        "query_metric",
        "compare_metric",
        "propose_hypothesis",
        "breakdown_metric",
        "update_hypothesis",
        "get_attribution_breakdown",
    ]
    tools = canonical[:4] if steps == 5 else canonical + (["list_hypotheses"] if steps == 8 else [])
    observations: list[dict[str, Any]] = []
    snapshots: list[dict[str, int]] = []

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def respond(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        items = contents(messages)
        evidence_ids = [
            value["evidence_id"]
            for _, value in items
            if isinstance(value, dict) and value.get("evidence_id")
        ]
        payload = injected(info)
        visible = {entry["id"] for entry in payload["relevant_evidence"]}
        assert {record.id for record in historical} <= visible
        assert not set(evidence_ids) & visible
        observations.append(
            {
                "stage": tools[len(items)] if len(items) < len(tools) else "diagnosis",
                "context_chars": len(
                    json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                ),
                "history_ids": sorted(visible),
                "sdk_usage_before": dict(snapshots[-1]),
            }
        )
        if len(items) == len(tools):
            response = finish(info, [r.id for r in historical] + evidence_ids)
        else:
            name = tools[len(items)]
            args: dict[str, Any]
            if name == "propose_hypothesis":
                args = {
                    "statement": "The metric change needs explanation",
                    "supporting_evidence_ids": evidence_ids[:1],
                    "contradicting_evidence_ids": evidence_ids[1:2],
                    "reason": "Both sides reviewed",
                }
            elif name == "update_hypothesis":
                proposed = next(value for tool, value in items if tool == "propose_hypothesis")
                args = {
                    "hypothesis_id": proposed["id"],
                    "status": "WEAKENED",
                    "supporting_evidence_ids": evidence_ids[:1],
                    "contradicting_evidence_ids": evidence_ids[1:],
                    "reason": "Aggregate evidence needs further investigation",
                    "confidence": "low",
                }
            elif name == "list_hypotheses":
                args = {}
            else:
                query: dict[str, Any] = {"time_range": CURRENT}
                if name != "get_attribution_breakdown":
                    query["metric"] = "CTR"
                if name == "compare_metric":
                    query = {
                        "metric": "CTR",
                        "current_period": CURRENT,
                        "baseline_period": BASELINE,
                    }
                if name in {"breakdown_metric", "get_attribution_breakdown"}:
                    query["dimension"] = "CHANNEL"
                args = {"request": query}
            response = ModelResponse(parts=[ToolCallPart(name, args)])
        response.usage = RequestUsage(input_tokens=1200, output_tokens=200)
        return response

    settings = config()
    async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as http:
        investigator = GrowthInvestigator(
            settings,
            AzurePiiGuardrail(settings, http),
            PulseFlowApiClient(settings, http),
            model=FunctionModel(respond),
        )

        @investigator._agent.instructions
        async def observe_usage(ctx: RunContext[AgentDependencies]) -> str:
            snapshots.append(
                {
                    "requests": ctx.usage.requests,
                    "input_tokens": ctx.usage.input_tokens,
                    "output_tokens": ctx.usage.output_tokens,
                }
            )
            return ""

        result = await investigator.run("Continue reviewing CTR", workspace_factory=factory)
    metrics = investigator.telemetry.metrics
    record = {
        "requested_steps": steps,
        "model_calls": len(observations),
        "status": workspace.status,
        "requests": metrics.model_requests,
        "sdk_total_tokens": metrics.tokens,
        "java_calls": server.calls,
        "trajectory": result.tool_trajectory,
        "evidence_references": result.diagnosis.evidence_ids,
        "reference_valid": set(result.diagnosis.evidence_ids) <= workspace.evidence_ids,
        "observations": observations,
        "usage_mode": "explicit_fixture_usage",
    }
    investigator.telemetry.shutdown()
    return record


@pytest.mark.parametrize("steps", [5, 7, 8])
async def test_multiple_requests_share_sdk_usage_without_context_budget_abort(steps: int) -> None:
    record = await multi_request_investigation(steps)
    assert record["status"] == "COMPLETED"
    assert record["model_calls"] == record["requests"] == steps
    assert record["sdk_total_tokens"] == steps * 1400
    assert record["reference_valid"]
    assert len({row["context_chars"] for row in record["observations"]}) == 1
    assert record["observations"][-1]["sdk_usage_before"] == {
        "requests": steps - 1,
        "input_tokens": (steps - 1) * 1200,
        "output_tokens": (steps - 1) * 200,
    }


@pytest.mark.parametrize(
    ("kind", "input_per_response", "output_per_response", "expected_calls"),
    [
        ("requests", 100, 20, 8),
        ("input", 4000, 100, 4),
        ("output", 100, 1500, 3),
        ("tools", 100, 20, 7),
    ],
)
async def test_sdk_native_run_limits_still_stop_without_custom_accounting(
    kind: str,
    input_per_response: int,
    output_per_response: int,
    expected_calls: int,
) -> None:
    settings = config()
    limits = create_usage_limits(settings)
    assert (
        limits.request_limit,
        limits.tool_calls_limit,
        limits.input_tokens_limit,
        limits.output_tokens_limit,
    ) == (8, 12, 12000, 4000)
    calls = 0
    snapshots: list[tuple[int, int, int]] = []
    workspace = InvestigationWorkspace(
        goal="Review CTR", evidence=[fact(60)], hypotheses=[hypothesis([], [fact(60).id])]
    )

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def loop(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        assert injected(info)["relevant_evidence"][0]["id"] == fact(60).id
        return ModelResponse(
            parts=[
                ToolCallPart("list_hypotheses", {}, tool_call_id=f"call-{calls}-{i}")
                for i in range(2 if kind == "tools" else 1)
            ],
            usage=RequestUsage(input_tokens=input_per_response, output_tokens=output_per_response),
        )

    async with httpx.AsyncClient() as http:
        investigator = GrowthInvestigator(
            settings,
            AzurePiiGuardrail(settings, http),
            PulseFlowApiClient(settings, http),
            model=FunctionModel(loop),
        )

        @investigator._agent.instructions
        async def observe_usage(ctx: RunContext[AgentDependencies]) -> str:
            snapshots.append((ctx.usage.requests, ctx.usage.input_tokens, ctx.usage.output_tokens))
            return ""

        result = await investigator.run("Continue", workspace_factory=factory)
    assert calls == expected_calls
    assert workspace.status == "BUDGET_EXHAUSTED"
    assert result.diagnosis.summary == "Investigation stopped at its usage budget."
    assert result.diagnosis.confidence == "low"
    metrics = investigator.telemetry.metrics
    assert metrics.model_requests == calls
    assert metrics.tokens == calls * (input_per_response + output_per_response)
    assert snapshots[:calls] == [
        (i, i * input_per_response, i * output_per_response) for i in range(calls)
    ]
    if kind == "tools":
        assert metrics.tool_calls == 12
    investigator.telemetry.shutdown()


def test_current_chat_provider_has_no_supported_preflight_and_old_configs_are_removed() -> None:
    from pydantic_ai.models import Model
    from pydantic_ai.models.openai import OpenAIChatModel, OpenAIResponsesModel

    assert OpenAIChatModel.count_tokens is Model.count_tokens
    assert OpenAIResponsesModel.count_tokens is not Model.count_tokens
    assert not create_usage_limits(config()).count_tokens_before_request
    assert (
        not {
            "pulseflow_agent_context_tokens",
            "pulseflow_agent_context_reserve_tokens",
            "pulseflow_agent_context_window_tokens",
        }
        & AgentSettings.model_fields.keys()
    )


@pytest.mark.parametrize(
    ("provider_code", "safe_code", "summary"),
    [
        (
            "context_length_exceeded",
            "model_context_window_exceeded",
            "Model context window exceeded.",
        ),
        ("invalid_request", "model_provider_request_failed", "Investigation failed."),
    ],
)
async def test_provider_request_failure_is_classified_redacted_and_not_retried(
    provider_code: str,
    safe_code: str,
    summary: str,
    database_url: str,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    from pydantic_ai import models
    from pydantic_ai.models.openai import OpenAIChatModel
    from pydantic_ai.providers.openai import OpenAIProvider

    from pulseflow_agent.agent import growth_investigator
    from tests.test_foundation import settings as provider_settings

    monkeypatch.setattr(models, "ALLOW_MODEL_REQUESTS", True)
    settings = config(database_url)
    engine = create_async_engine(database_url)
    repo = SqlAlchemyInvestigationRepository(engine)
    created = await repo.create("Review")
    workspace = InvestigationWorkspace(goal=created.goal, id=created.id, repository=repo)
    calls = 0
    private_body = "CONFIDENTIAL_EVIDENCE_FROM_PROVIDER_BODY"

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    def reject(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            400,
            json={
                "error": {
                    "code": provider_code,
                    "type": "invalid_request_error",
                    "message": private_body,
                }
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(reject)) as model_http:
        monkeypatch.setattr(
            growth_investigator,
            "OpenAIProvider",
            lambda **kwargs: OpenAIProvider(**kwargs, http_client=model_http),
        )
        model = growth_investigator.create_model(
            provider_settings(real=True, pulseflow_agent_base_url="https://model.invalid/v1")
        )
        assert isinstance(model, OpenAIChatModel)
        assert model.client.max_retries == 0

        investigator = GrowthInvestigator(
            settings,
            AzurePiiGuardrail(settings, model_http),
            PulseFlowApiClient(settings, model_http),
            model=model,
        )
        with pytest.raises(RuntimeError, match=safe_code) as error:
            await investigator.run("Continue", workspace_factory=factory)
    assert calls == 1
    assert str(error.value) == safe_code
    assert error.value.__suppress_context__
    loaded = await repo.load(created.id)
    assert loaded.status == "FAILED"  # Provider window, not SDK cumulative budget.
    assert loaded.final_diagnosis and loaded.final_diagnosis.summary == summary
    assert private_body not in loaded.model_dump_json() + caplog.text + str(error.value)
    assert investigator.telemetry.metrics.budgets == 0
    investigator.telemetry.shutdown()
    await engine.dispose()
