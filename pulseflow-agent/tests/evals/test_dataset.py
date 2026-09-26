"""22 fixture cases plus an explicitly opt-in real-provider evaluation."""

import json
import os
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import SecretStr
from pydantic_ai import models
from pydantic_ai.models.function import FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from tests.evals.harness import (
    FAMILIES,
    EvalCase,
    FixtureServer,
    OfflinePolicy,
    unsupported_numbers,
)

CASES = [
    EvalCase.model_validate(value)
    for value in json.loads((Path(__file__).parent / "cases.json").read_text(encoding="utf-8"))
]


async def evaluate(real: bool) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    for case in CASES:
        if real:
            settings = AgentSettings()  # type: ignore[call-arg]
            if settings.is_test_model:
                raise ValueError(
                    "REAL_AGENT_EVAL requires a real configured provider and Azure PII"
                )
            settings = settings.model_copy(
                update={
                    "pulseflow_agent_max_model_requests": 1 if case.category == "budget" else 8,
                    "pulseflow_agent_max_tool_calls": max(1, case.max_tool_calls),
                }
            )
        else:
            settings = AgentSettings.model_validate(
                {
                    "pulseflow_agent_env": "test",
                    "pulseflow_agent_model": "test",
                    "pulseflow_java_base_url": "http://java.internal:8080",
                    "pulseflow_agent_internal_token": SecretStr("eval-machine-secret"),
                    "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
                    "pulseflow_agent_max_model_requests": 1 if case.category == "budget" else 8,
                    "pulseflow_agent_max_tool_calls": max(1, case.max_tool_calls),
                }
            )
        telemetry = Telemetry()
        server = FixtureServer(case)
        async with httpx.AsyncClient(transport=httpx.MockTransport(server)) as java:
            async with httpx.AsyncClient() as azure:
                investigator = GrowthInvestigator(
                    settings,
                    AzurePiiGuardrail(settings, azure),
                    PulseFlowApiClient(settings, java, telemetry.metrics),
                    model=None if real else FunctionModel(OfflinePolicy(case).respond),
                    telemetry=telemetry,
                )
                try:
                    result = await investigator.run(case.user_goal)
                except PiiBlockedError:
                    assert case.expected_status == "PII_BLOCKED", case.id
                    assert server.calls == 0, case.id
                    records.append(
                        {
                            "id": case.id,
                            "status": "PII_BLOCKED",
                            "reference_valid": True,
                            "unsupported_claims": 0,
                            "tool_trajectory": [],
                            "quality": telemetry.metrics.snapshot(),
                        }
                    )
                    telemetry.shutdown()
                    continue
        quality = telemetry.metrics.snapshot()
        status = (
            "BUDGET_EXHAUSTED" if quality["budget_exhausted_rate"] == 1 else result.diagnosis.status
        )
        assert status == case.expected_status, case.id
        evidence_ids = {item.id for item in result.evidence}
        references = set(result.diagnosis.evidence_ids)
        for finding in result.diagnosis.findings:
            references.update(finding.evidence_ids)
        valid = references.issubset(evidence_ids)
        assert valid, case.id
        assert len(result.evidence) >= case.expected_evidence_constraints.minimum, case.id
        assert all(
            item.java_query_id
            and item.source
            in {"campaign-facts", "campaign-summary", "attribution-record", "audience-preview"}
            for item in result.evidence
        ), case.id
        claims = " ".join(
            [result.diagnosis.summary, *(finding.claim for finding in result.diagnosis.findings)]
        )
        unsupported = sum(
            word.casefold() in claims.casefold() for word in case.expected_forbidden_conclusions
        )
        unsupported += unsupported_numbers(
            claims, [item.observation for item in result.evidence], case.user_goal
        )
        assert unsupported == 0, case.id
        if case.category == "refute":
            assert any(item.status == "REJECTED" for item in result.hypotheses), case.id
        families = {FAMILIES[name] for name in result.tool_trajectory}
        assert set(case.expected_required_tool_families).issubset(families), case.id
        assert len(result.tool_trajectory) <= case.max_tool_calls, case.id
        if status in {"INSUFFICIENT_EVIDENCE", "BUDGET_EXHAUSTED"}:
            assert result.diagnosis.confidence == "low", case.id
            assert result.diagnosis.unresolved_questions, case.id
        records.append(
            {
                "id": case.id,
                "status": status,
                "reference_valid": valid,
                "unsupported_claims": unsupported,
                "tool_trajectory": result.tool_trajectory,
                "quality": quality,
            }
        )
        telemetry.shutdown()
    summary: dict[str, float | None] = {}
    keys = list(records[0]["quality"])
    for key in keys:
        available = [
            record["quality"][key] for record in records if record["quality"][key] is not None
        ]
        summary[key] = sum(available) / len(available) if available else None
    summary["evidence_reference_valid_rate"] = sum(
        record["reference_valid"] for record in records
    ) / len(records)
    summary["unsupported_claim_rate"] = sum(
        record["unsupported_claims"] > 0 for record in records
    ) / len(records)
    summary["evaluation_pass_rate"] = 1.0
    return {
        "mode": "real" if real else "offline_control_flow",
        "cases": records,
        "quality_metrics": summary,
    }


@pytest.mark.asyncio
async def test_offline_eval_dataset(tmp_path: Path) -> None:
    assert len(CASES) >= 20
    report = await evaluate(False)
    path = Path(os.getenv("PULSEFLOW_AGENT_EVAL_REPORT", str(tmp_path / "eval-report.json")))
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")


def test_numeric_claim_oracle_rejects_new_values() -> None:
    assert unsupported_numbers("CTR is 9999", ["CTR value=0.2 sampleSize=100"], "") == 1
    assert unsupported_numbers("CTR is 20%", ["CTR value=0.2 sampleSize=100"], "") == 0


@pytest.mark.asyncio
@pytest.mark.skipif(os.getenv("REAL_AGENT_EVAL") != "true", reason="real eval is explicitly opt-in")
async def test_real_provider_eval_dataset(tmp_path: Path) -> None:
    previous = models.ALLOW_MODEL_REQUESTS
    models.ALLOW_MODEL_REQUESTS = True
    try:
        report = await evaluate(True)
        (tmp_path / "real-eval-report.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )
    finally:
        models.ALLOW_MODEL_REQUESTS = previous
