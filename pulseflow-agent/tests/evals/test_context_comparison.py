"""Counterfactual context regression using the existing FunctionModel eval harness.

The deterministic evidence-reading policy measures retrieval/control flow, NOT
real-provider diagnostic accuracy or production token consumption.
"""

import copy
import json
import os
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic_ai import ModelMessage, ModelResponse
from pydantic_ai.models.function import AgentInfo, FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.agent.instructions import GROWTH_INVESTIGATOR_INSTRUCTIONS
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.investigation import AgentMessage, InvestigationWorkspace
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from tests.agent.test_context_integration import config, finish
from tests.evals.harness import unsupported_numbers
from tests.workspace.test_context import NOW, assessment, fact, hypothesis


def legacy_context(workspace: InvestigationWorkspace) -> str:
    """Pre-change context_text, preserved ONLY as the comparison control."""
    prior = (
        workspace.messages[:-1]
        if workspace.messages and workspace.messages[-1].role == "USER"
        else workspace.messages
    )
    recent = [f"{item.role}: {item.content}" for item in prior[-4:]]
    facts = [
        f"{item.id} "
        f"[{'CURRENT' if item.scope_version == workspace.scope_version else 'BACKGROUND'}]: "
        f"{item.observation}"
        for item in workspace.evidence[-12:]
    ]
    hypotheses = [
        f"{item.id} [{item.status}]: {item.statement}" for item in workspace.hypotheses[-8:]
    ]
    return (
        f"Investigation goal: {workspace.goal}\nCurrent scope: {workspace.scope or 'overall'}\n"
        + "Prior visible conversation:\n"
        + "\n".join(recent)
        + "\nEvidence (BACKGROUND is historical, not proof for current scope):\n"
        + "\n".join(facts)
        + "\nHypotheses:\n"
        + "\n".join(hypotheses)
    )[:6000]


def scenario(name: str) -> tuple[InvestigationWorkspace, list[str]]:
    workspace = InvestigationWorkspace(goal="Review aggregate facts")
    if name == "empty":
        return workspace, []
    target = fact(0)
    workspace.evidence = [target]
    if name == "buried_metric":
        workspace.evidence.extend(fact(i, "CONVERSION_RATE") for i in range(1, 25))
    elif name == "opposing_evidence":
        opposite = fact(1, observation="CTR rows=1: ALL value=0.2000 sampleSize=100")
        workspace.evidence.extend([opposite, *[fact(i, "CONVERSIONS") for i in range(2, 25)]])
        workspace.hypotheses = [hypothesis([target.id], [opposite.id])]
        return workspace, [target.id, opposite.id]
    elif name == "scope":
        workspace.scope_version = 1
        workspace.scope = "New campaign"
        workspace.evidence.append(fact(1, scope_version=1))
        workspace.hypotheses = [hypothesis([], [target.id])]
        workspace.messages = [
            AgentMessage(
                role="ASSISTANT",
                content="Old finding",
                created_at=NOW,
                diagnosis=assessment([target.id]),
            )
        ]
        return workspace, [workspace.evidence[1].id]
    elif name == "protected_overflow":
        target.observation = "CTR business row value=0.123456789000; " * 300
        workspace.hypotheses = [hypothesis([], [target.id])]
    return workspace, [target.id]


async def compare_run(name: str, legacy: bool) -> dict[str, Any]:
    original, required = scenario(name)
    workspace = copy.deepcopy(original)
    question = "Review CTR"
    contexts: list[str] = []
    calls = 0
    returned_ids: list[str] = []

    async def factory(_: str) -> InvestigationWorkspace:
        return workspace

    async def respond(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        assert info.instructions
        marker = "Investigation goal:" if legacy else '{"context_policy":'
        text = info.instructions[info.instructions.index(marker) :]
        contexts.append(text)
        visible = [
            item.id
            for item in original.evidence
            if item.id in text
            and item.observation in text
            and item.scope_version == original.scope_version
        ]
        # The SAME fixture policy reads only complete, current-scope evidence in
        # either renderer. Contradictory facts still cannot establish a diagnosis.
        complete = bool(required) and set(required) <= set(visible)
        returned_ids.extend(ref for ref in required if ref in visible)
        ids = returned_ids if complete and name != "opposing_evidence" else []
        return finish(info, ids)

    settings = config()
    telemetry = Telemetry()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: pytest.fail("Context-only comparisons must not query Java")
        )
    ) as http:
        guardrail = AzurePiiGuardrail(settings, http)
        investigator = GrowthInvestigator(
            settings,
            guardrail,
            PulseFlowApiClient(settings, http),
            model=FunctionModel(respond),
            telemetry=telemetry,
        )
        if legacy:

            async def prior_instructions() -> str:
                text = legacy_context(workspace)
                await guardrail.check(text)
                return text

            # SDK public testing API replaces only the instructions. The same
            # tools, output validators, run wrapper and fixture policy still run.
            with investigator._agent.override(
                instructions=[GROWTH_INVESTIGATOR_INSTRUCTIONS, prior_instructions]
            ):
                result = await investigator.run(question, workspace_factory=factory)
        else:
            result = await investigator.run(question, workspace_factory=factory)
    text = contexts[0] if contexts else ""
    complete_ids = {
        record.id
        for record in original.evidence
        if record.id in text and record.observation in text
    }
    actual_refs = set(result.diagnosis.evidence_ids)
    valid_refs = actual_refs <= original.evidence_ids
    assert valid_refs
    unsupported = unsupported_numbers(
        result.diagnosis.summary, [e.observation for e in original.evidence], question
    )
    assert unsupported == 0
    quality = telemetry.metrics.snapshot()
    record = {
        "case": name,
        "renderer": "legacy" if legacy else "business_aware",
        "key_evidence_recall": len(set(required) & complete_ids) / len(required)
        if required
        else None,
        "reference_valid": valid_refs,
        "reference_count": len(actual_refs),
        "scope_isolated": actual_refs <= original.evidence_ids,
        "diagnosis_status": result.diagnosis.status,
        "run_status": workspace.status,
        "model_calls": calls,
        "context_chars": len(text),
        "history_soft_target_exceeded": not legacy
        and len(text) > settings.pulseflow_agent_context_target_chars,
        "sdk_synthetic_total_tokens": quality["avg_tokens"],
        "partial_evidence_units": sum(
            e.id in text and e.id not in complete_ids for e in original.evidence
        ),
        "storage_records_unchanged": workspace.evidence == original.evidence,
    }
    telemetry.shutdown()
    return record


async def test_context_before_after_comparison(
    tmp_path: Path,
) -> None:
    records = []
    for name in (
        "empty",
        "small",
        "buried_metric",
        "opposing_evidence",
        "scope",
        "protected_overflow",
    ):
        before = await compare_run(name, True)
        after = await compare_run(name, False)
        assert before["reference_valid"] and after["reference_valid"]
        assert after["scope_isolated"] and after["storage_records_unchanged"]
        assert after["partial_evidence_units"] == 0
        assert after["model_calls"] <= before["model_calls"]
        if name in {"empty", "small", "scope", "opposing_evidence"}:
            assert after["diagnosis_status"] == before["diagnosis_status"]
        if name in {"buried_metric", "opposing_evidence"}:
            assert before["key_evidence_recall"] == 0 and after["key_evidence_recall"] == 1
        if name == "protected_overflow":
            assert before["partial_evidence_units"] == 1
            assert after["run_status"] == "COMPLETED" and after["model_calls"] == 1
            assert after["key_evidence_recall"] == 1 and after["history_soft_target_exceeded"]
        records.extend([before, after])
    report = {
        "mode": "offline_context_control_flow",
        "production_provider_acceptance": False,
        "token_note": (
            "context_chars is history length only. "
            "FunctionModel synthetic usage excludes instructions; "
            "it is not provider billing or production token usage."
        ),
        "cases": records,
    }
    path = Path(os.getenv("PULSEFLOW_CONTEXT_EVAL_REPORT", str(tmp_path / "context-eval.json")))
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")
