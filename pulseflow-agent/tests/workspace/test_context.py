"""Business relevance, reference closure and complete-unit context budgets."""

import json
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import pytest

from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.investigation import (
    AgentMessage,
    Diagnosis,
    Evidence,
    Finding,
    Hypothesis,
    InvestigationWorkspace,
)
from pulseflow_agent.workspace.context import (
    ContextBudgetExceeded,
    ContextBuilder,
    context_token_budget,
    estimate_tokens,
)
from tests.evals.harness import BASELINE, CURRENT

NOW = datetime(2026, 9, 26, tzinfo=UTC)


def fact(
    index: int,
    metric: str = "CTR",
    *,
    campaign: str | None = None,
    channel: str | None = None,
    scope_version: int = 0,
    observation: str | None = None,
    period: dict[str, str] | None = None,
) -> Evidence:
    filters = [
        {"field": field, "operator": "EQ", "values": [value]}
        for field, value in (("CAMPAIGN_ID", campaign), ("CHANNEL", channel))
        if value
    ]
    return Evidence(
        id=str(UUID(int=index + 1)),
        tool_name="query_metric",
        source="campaign-facts",
        observation=observation or f"{metric} rows=1: ALL value=0.1234567890123000 sampleSize=100",
        query={"metric": metric, "timeRange": period or CURRENT, "filters": filters},
        data_version="facts:v1",
        warnings=["row_limit_reached"],
        trace_id=None,
        collected_at=NOW,
        java_query_id=str(UUID(int=index + 1000)),
        scope_version=scope_version,
    )


def hypothesis(support: list[str], contradict: list[str], *, version: int = 0) -> Hypothesis:
    return Hypothesis(
        id=str(UUID(int=9000)),
        statement="CTR may have declined; cause remains unverified",
        status="WEAKENED",
        supporting_evidence_ids=support,
        contradicting_evidence_ids=contradict,
        reason="Both sides need review",
        confidence="low",
        scope_version=version,
        created_at=NOW,
        updated_at=NOW,
    )


def assessment(ids: list[str]) -> Diagnosis:
    return Diagnosis(
        status="DIAGNOSED",
        summary="A prior aggregate finding; causality is unproven",
        findings=[Finding(claim="Observed aggregate", evidence_ids=ids)],
        evidence_ids=ids,
        unresolved_questions=["What explains the change?"],
        confidence="medium",
        recommended_next_action=None,
    )


def context(
    workspace: InvestigationWorkspace, question: str = "Continue", budget: int = 100000
) -> dict[str, Any]:
    payload = json.loads(
        ContextBuilder(workspace, question).build(
            scope=workspace.scope,
            scope_version=workspace.scope_version,
            token_budget=budget,
        )
    )
    assert isinstance(payload, dict)
    return payload


def test_new_investigation_has_no_invented_facts_or_current_question_history() -> None:
    workspace = InvestigationWorkspace(goal="Investigate CTR")
    workspace.messages.append(AgentMessage(role="USER", content="Continue", created_at=NOW))
    result = context(workspace)
    assert result["relevant_evidence"] == []
    assert result["hypotheses"] == []
    assert result["prior_diagnosis"] is None
    assert result["recent_conversation"] == []
    assert "not confirmed facts" in result["context_policy"]


def test_question_metric_beats_recency_and_original_goal() -> None:
    target = fact(0, "CONVERSION_RATE")
    workspace = InvestigationWorkspace(
        goal="CTR decline", evidence=[target, *[fact(index) for index in range(1, 21)]]
    )
    result = context(workspace, "转化率如何？", budget=2000)
    assert result["relevant_evidence"][0]["id"] == target.id
    assert len(result["relevant_evidence"]) < len(workspace.evidence)
    assert result["business_context"]["metrics"] == ["CONVERSION_RATE"]


@pytest.mark.parametrize(
    ("question", "target", "other"),
    [
        ("PUSH", fact(0, channel="PUSH"), fact(1, channel="IN_APP")),
        ("campaign ID: 9", fact(0, campaign="9"), fact(1, campaign="19")),
        ("活动9", fact(0, campaign="9"), fact(1, campaign="19")),
        ("2026-09-02 to 2026-09-06", fact(0), fact(1, period=BASELINE)),
    ],
)
def test_structured_channel_campaign_and_period_rank_first(
    question: str, target: Evidence, other: Evidence
) -> None:
    result = context(InvestigationWorkspace(goal="Review", evidence=[target, other]), question)
    assert result["relevant_evidence"][0]["id"] == target.id


def test_comparison_baseline_is_a_relevant_period_without_parsing_observation() -> None:
    comparison = fact(0)
    comparison.tool_name = "compare_metric"
    comparison.query = {"metric": "CTR", "currentPeriod": CURRENT, "baselinePeriod": BASELINE}
    comparison.observation = "Keep the original comparison; no reverse parsing"
    result = context(
        InvestigationWorkspace(goal="Review", evidence=[comparison, fact(1)]),
        "2026-08-25 to 2026-08-29",
    )
    assert result["relevant_evidence"][0]["id"] == comparison.id
    assert result["relevant_evidence"][0]["query"] == comparison.query


@pytest.mark.parametrize(
    ("tool", "query", "question"),
    [
        ("get_campaign_performance", {"campaignId": 9}, "Campaign 9"),
        (
            "preview_audience",
            {"audience_conditions": 2, "channel": "PUSH", "objective": "RETENTION"},
            "推送人群",
        ),
        ("get_attribution_breakdown", {"timeRange": CURRENT, "dimension": "CHANNEL"}, "归因转化"),
    ],
)
def test_other_tools_reuse_stored_selectors(
    tool: str, query: dict[str, object], question: str
) -> None:
    target = fact(0).model_copy(update={"tool_name": tool, "query": query})
    result = context(InvestigationWorkspace(goal="Review", evidence=[target, fact(1)]), question)
    assert result["relevant_evidence"][0]["id"] == target.id
    assert result["relevant_evidence"][0]["observation"] == target.observation


def test_both_sides_and_valid_diagnosis_references_are_protected_with_provenance() -> None:
    support, contradiction, diagnosed = fact(0), fact(1), fact(2)
    workspace = InvestigationWorkspace(
        goal="Review",
        evidence=[
            support,
            contradiction,
            diagnosed,
            *[fact(index, "CONVERSION_RATE") for index in range(3, 24)],
        ],
        hypotheses=[hypothesis([support.id], [contradiction.id])],
        messages=[
            AgentMessage(
                role="ASSISTANT",
                content="Prior finding",
                created_at=NOW,
                diagnosis=assessment([diagnosed.id]),
            ),
        ],
    )
    before = [item.model_dump_json() for item in workspace.evidence + workspace.hypotheses]
    result = context(workspace, "转化率", budget=5000)
    ids = {item["id"] for item in result["relevant_evidence"]}
    assert {support.id, contradiction.id, diagnosed.id}.issubset(ids)
    assert result["hypotheses"][0]["supporting_evidence_ids"] == [support.id]
    assert result["hypotheses"][0]["contradicting_evidence_ids"] == [contradiction.id]
    assert result["hypotheses"][0]["status"] == "WEAKENED"
    for item in result["relevant_evidence"]:
        original = next(record for record in workspace.evidence if record.id == item["id"])
        for key in ("observation", "query", "data_version", "source", "warnings", "java_query_id"):
            assert item[key] == getattr(original, key)
    assert before == [item.model_dump_json() for item in workspace.evidence + workspace.hypotheses]
    assert result["unresolved_questions"] == [
        {"question": "What explains the change?", "scope": "CURRENT"}
    ]


def test_historical_scope_is_background_including_hypotheses_and_diagnosis() -> None:
    old = fact(0)
    workspace = InvestigationWorkspace(
        goal="Review",
        scope="New campaign",
        scope_version=1,
        evidence=[old, fact(1, scope_version=1)],
        hypotheses=[hypothesis([], [old.id])],
        messages=[
            AgentMessage(
                role="ASSISTANT",
                content="Old conclusion",
                created_at=NOW,
                diagnosis=assessment([old.id]),
            ),
        ],
    )
    result = context(workspace)
    assert (
        next(item for item in result["relevant_evidence"] if item["id"] == old.id)["scope"]
        == "BACKGROUND"
    )
    assert result["hypotheses"][0]["scope"] == "BACKGROUND"
    assert result["prior_diagnosis"]["scope"] == "BACKGROUND"
    assert result["unresolved_questions"][0]["scope"] == "BACKGROUND"
    assert workspace.evidence_ids == {workspace.evidence[1].id}


def test_invalid_cross_scope_hypothesis_is_not_presented_as_current() -> None:
    old = fact(0)
    workspace = InvestigationWorkspace(
        goal="Review",
        scope_version=1,
        evidence=[old],
        hypotheses=[hypothesis([old.id], [], version=1).model_copy(update={"status": "SUPPORTED"})],
    )
    result = context(workspace)
    assert result["hypotheses"][0]["scope"] == "BACKGROUND"
    assert all(item["scope"] == "BACKGROUND" for item in result["relevant_evidence"])


def test_unknown_words_or_legacy_selectors_conservatively_keep_whole_observations() -> None:
    unknown = fact(0).model_copy(update={"query": {"legacy": "unknown"}})
    result = context(
        InvestigationWorkspace(goal="Review", evidence=[unknown, fact(1)]),
        "A new question without reliable selectors",
    )
    assert {item["id"] for item in result["relevant_evidence"]} == {unknown.id, fact(1).id}
    assert (
        next(item for item in result["relevant_evidence"] if item["id"] == unknown.id)["query"]
        == unknown.query
    )


def test_whole_units_keep_precision_and_do_not_delete_unselected_evidence() -> None:
    workspace = InvestigationWorkspace(goal="Review", evidence=[fact(index) for index in range(10)])
    text = ContextBuilder(workspace, "CTR").build(scope=None, scope_version=0, token_budget=2400)
    result = json.loads(text)
    assert estimate_tokens(text) <= 2400
    assert 0 < len(result["relevant_evidence"]) < 10
    for item in result["relevant_evidence"]:
        assert item["observation"] == workspace.evidence[0].observation
        assert len(item["id"]) == 36
        assert "0.1234567890123000" in item["observation"]
    assert len(workspace.evidence) == 10
    assert result["selection"]["omitted_evidence"] == 10 - len(result["relevant_evidence"])


def test_short_context_is_not_compressed_and_historical_reference_units_are_closed() -> None:
    record = fact(0)
    workspace = InvestigationWorkspace(
        goal="Review", evidence=[record], hypotheses=[hypothesis([], [record.id])]
    )
    result = context(workspace)
    assert result["relevant_evidence"][0]["observation"] == record.observation
    assert result["hypotheses"][0]["statement"] == workspace.hypotheses[0].statement
    workspace.scope_version = 1
    for budget in (1400, 2300, 4000):
        selected = context(workspace, budget=budget)
        known = {item["id"] for item in selected["relevant_evidence"]}
        for item in selected["hypotheses"]:
            assert (
                set(item["supporting_evidence_ids"] + item["contradicting_evidence_ids"]) <= known
            )


def test_protected_oversize_unit_raises_explicitly_without_leaking_its_contents() -> None:
    record = fact(0, observation="A complete observation " * 500 + "value=0.000012340000")
    workspace = InvestigationWorkspace(
        goal="Review", evidence=[record], hypotheses=[hypothesis([], [record.id])]
    )
    with pytest.raises(ContextBudgetExceeded) as failure:
        context(workspace, budget=4000)
    assert failure.value.required > failure.value.budget == 4000
    assert record.id not in str(failure.value)
    assert "0.000012340000" not in str(failure.value)
    workspace.hypotheses.clear()
    assert context(workspace, budget=4000)["relevant_evidence"] == []


def test_oversize_historical_questions_can_be_removed_before_current_evidence() -> None:
    workspace = InvestigationWorkspace(
        goal="Review",
        scope_version=1,
        evidence=[fact(0, scope_version=1)],
        messages=[
            AgentMessage(
                role="ASSISTANT",
                content="Old assessment",
                created_at=NOW,
                diagnosis=assessment([]).model_copy(
                    update={"unresolved_questions": ["Old gap " * 3000]}
                ),
            )
        ],
    )
    result = context(workspace, budget=2400)
    assert result["relevant_evidence"]
    assert result["unresolved_questions"] == []


def test_run_entry_snapshot_excludes_new_tool_results_and_reclassifies_after_scope_change() -> None:
    old, new = fact(0), fact(1)
    workspace = InvestigationWorkspace(goal="Review", evidence=[old])
    builder = ContextBuilder(workspace, "Continue")
    workspace.evidence.append(new)
    old.query["metric"] = "CONVERSIONS"
    workspace.messages.append(AgentMessage(role="USER", content="Continue", created_at=NOW))
    text = builder.build(scope=None, scope_version=0, token_budget=4000)
    assert old.id in text and new.id not in text
    assert json.loads(text)["relevant_evidence"][0]["query"]["metric"] == "CTR"
    text = builder.build(scope="Different scope", scope_version=1, token_budget=4000)
    assert all(item["scope"] == "BACKGROUND" for item in json.loads(text)["relevant_evidence"])


def test_input_usage_model_window_and_fixed_instructions_limit_the_context_budget() -> None:
    settings = AgentSettings.model_validate(
        {
            "pulseflow_agent_model": "test",
            "pulseflow_java_base_url": "http://java.internal:8080",
            "pulseflow_agent_database_url": "sqlite+aiosqlite:///:memory:",
        }
    )
    assert (
        context_token_budget(
            settings, input_tokens_used=0, output_tokens_used=0, fixed_text="x" * 1000
        )
        == 4000
    )
    assert (
        context_token_budget(
            settings, input_tokens_used=6000, output_tokens_used=100, fixed_text="x" * 1000
        )
        == 1000
    )
    assert (
        context_token_budget(
            settings, input_tokens_used=9000, output_tokens_used=100, fixed_text="x" * 1000
        )
        == 0
    )
    smaller = settings.model_copy(update={"pulseflow_agent_context_window_tokens": 8000})
    assert (
        context_token_budget(
            smaller, input_tokens_used=0, output_tokens_used=0, fixed_text="x" * 1000
        )
        == 1000
    )
    assert estimate_tokens("调查 CTR") == len("调查 CTR".encode())


def test_authorized_facts_are_complete_mandatory_units_in_the_same_budget() -> None:
    builder = ContextBuilder(InvestigationWorkspace(goal="Review"), "Continue")
    facts = [{"type": "COUPON", "discount": "10.0000", "description": "Operator fact"}]
    rendered = builder.build(
        scope=None, scope_version=0, token_budget=4000, operator_authorized_promotion_facts=facts
    )
    assert json.loads(rendered)["operator_authorized_promotion_facts"] == facts
    with pytest.raises(ContextBudgetExceeded):
        builder.build(
            scope=None,
            scope_version=0,
            token_budget=1200,
            operator_authorized_promotion_facts=[{"description": "Complete unit " * 100}],
        )
