"""Fixture-backed evaluation; the fake policy is not production Agent logic."""

import json
import re
from decimal import Decimal
from typing import Any
from uuid import uuid4

import httpx
from pydantic import BaseModel
from pydantic_ai import ModelMessage, ModelResponse, TextPart, ToolCallPart, ToolReturnPart
from pydantic_ai.models.function import AgentInfo

CURRENT = {"fromInclusive": "2026-09-01T00:00:00+08:00", "toExclusive": "2026-09-08T00:00:00+08:00"}
BASELINE = {
    "fromInclusive": "2026-08-24T00:00:00+08:00",
    "toExclusive": "2026-08-31T00:00:00+08:00",
}
FAMILIES = {
    "query_metric": "query",
    "compare_metric": "comparison",
    "breakdown_metric": "breakdown",
    "get_campaign_performance": "performance",
    "get_attribution_breakdown": "attribution",
    "preview_audience": "audience",
    "propose_hypothesis": "workspace",
    "update_hypothesis": "workspace",
    "list_hypotheses": "workspace",
    "update_scope": "workspace",
}


def unsupported_numbers(claim: str, observations: list[str], selectors: str) -> int:
    """Detect introduced numeric claims; permit ratio-to-percent presentation only."""
    pattern = r"(?<![A-Za-z0-9])\d+(?:\.\d+)?"
    allowed = {Decimal(value) for value in re.findall(pattern, " ".join(observations) + selectors)}
    allowed.update(value * 100 for value in list(allowed) if 0 <= value <= 1)
    return sum(Decimal(value) not in allowed for value in re.findall(pattern, claim))


class Fixtures(BaseModel):
    mode: str
    metric: str
    current: Decimal
    baseline: Decimal
    sample_size: int


class EvidenceConstraints(BaseModel):
    minimum: int
    current_scope_only: bool
    java_source_only: bool


class EvalCase(BaseModel):
    id: str
    category: str
    user_goal: str
    fixtures: Fixtures
    expected_evidence_constraints: EvidenceConstraints
    expected_status: str
    expected_forbidden_conclusions: list[str]
    expected_required_tool_families: list[str]
    max_tool_calls: int


def metadata(source: str = "campaign-facts", warnings: list[str] | None = None) -> dict[str, Any]:
    return {
        "queryId": str(uuid4()),
        "generatedAt": "2026-09-26T00:00:00Z",
        "dataVersion": None,
        "source": source,
        "warnings": warnings or [],
    }


class FixtureServer:
    def __init__(self, case: EvalCase) -> None:
        self.case = case
        self.calls = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls += 1
        fixture = self.case.fixtures
        mode = fixture.mode
        if mode == "timeout":
            raise httpx.ReadTimeout("fixture timeout", request=request)
        if mode.isdigit():
            return httpx.Response(int(mode), json={"error": "fixture_error"})
        if mode == "malformed":
            return httpx.Response(200, json={"invalid": True})
        payload = json.loads(request.content) if request.content else {}
        path = request.url.path
        warnings = ["row_limit_reached"] if mode == "truncated" else []
        current = fixture.baseline if mode == "conflict" and self.calls > 1 else fixture.current
        if path.endswith("/metrics/compare"):
            delta = current - fixture.baseline
            relative = delta / fixture.baseline if fixture.baseline else None
            body: dict[str, Any] = {
                "metadata": metadata(
                    warnings=(["baseline_zero"] if relative is None else warnings)
                ),
                "metric": payload["metric"],
                "currentPeriod": payload["currentPeriod"],
                "baselinePeriod": payload["baselinePeriod"],
                "dimensions": payload.get("dimensions", []),
                "rows": [
                    {
                        "dimensions": {},
                        "current": str(current),
                        "baseline": str(fixture.baseline),
                        "absoluteDelta": str(delta),
                        "relativeDelta": str(relative) if relative is not None else None,
                        "currentSampleSize": fixture.sample_size,
                        "baselineSampleSize": fixture.sample_size,
                    }
                ],
                "sampleSize": fixture.sample_size,
            }
        elif "/metrics/" in path:
            dimensions = payload.get(
                "dimensions", [payload["dimension"]] if "dimension" in payload else []
            )
            dims = {dimensions[0]: "PUSH"} if dimensions else {}
            body = {
                "metadata": metadata(warnings=warnings),
                "metric": payload["metric"],
                "timeRange": payload["timeRange"],
                "dimensions": dimensions,
                "rows": []
                if mode == "empty"
                else [
                    {"dimensions": dims, "value": str(current), "sampleSize": fixture.sample_size}
                ],
                "sampleSize": fixture.sample_size,
            }
        elif path.endswith("/attribution/breakdown"):
            body = {
                "metadata": metadata("attribution-record"),
                "timeRange": payload["timeRange"],
                "dimension": payload["dimension"],
                "rows": [
                    {
                        "dimensions": {payload["dimension"]: "PUSH"},
                        "attributionCount": 10,
                        "uniqueConverters": 8,
                    }
                ],
            }
        elif path.endswith("/audience/preview"):
            body = {
                "metadata": metadata("audience-preview"),
                "estimatedCount": 42,
                "validation": {
                    "valid": True,
                    "needsConfirmation": False,
                    "errors": [],
                    "missingFields": [],
                },
            }
        else:
            campaign_id = int(path.split("/")[-2])
            body = {
                "metadata": metadata("campaign-summary", ["summary_unavailable"]),
                "campaignId": campaign_id,
                "available": False,
                "targetAudienceCount": None,
                "sentCount": None,
                "deliveredCount": None,
                "clickedCount": None,
                "convertedCount": None,
                "deliveryRate": None,
                "clickRate": None,
                "conversionRate": None,
                "calculatedAt": None,
            }
        if mode == "extra_pii":
            body["userId"] = 123456  # rejected by strict client schema, never returned to model
        return httpx.Response(200, json=body)


def contents(messages: list[ModelMessage]) -> list[tuple[str, Any]]:
    result: list[tuple[str, Any]] = []
    for message in messages:
        for part in message.parts:
            if isinstance(part, ToolReturnPart):
                value = part.content
                if isinstance(value, BaseModel):
                    value = value.model_dump(mode="json")
                if isinstance(value, str):
                    try:
                        value = json.loads(value)
                    except json.JSONDecodeError:
                        pass
                result.append((part.tool_name, value))
    return result


class OfflinePolicy:
    def __init__(self, case: EvalCase) -> None:
        self.case = case

    async def respond(self, messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        items = contents(messages)
        mode = self.case.fixtures.mode
        ids = [
            value["evidence_id"]
            for _, value in items
            if isinstance(value, dict) and value.get("evidence_id")
        ]
        if not items or mode == "budget":
            if mode == "missing_summary":
                return ModelResponse(
                    parts=[ToolCallPart("get_campaign_performance", {"campaign_id": 9})]
                )
            if mode == "compare":
                return self.call(
                    "compare_metric",
                    {
                        "metric": self.case.fixtures.metric,
                        "current_period": CURRENT,
                        "baseline_period": BASELINE,
                    },
                )
            return self.call(
                "query_metric", {"metric": self.case.fixtures.metric, "time_range": CURRENT}
            )
        if mode == "conflict" and len(items) == 1:
            return self.call("query_metric", {"metric": "CTR", "time_range": CURRENT})
        if mode == "refute":
            if len(items) == 1:
                return ModelResponse(
                    parts=[
                        ToolCallPart(
                            "propose_hypothesis",
                            {
                                "statement": "The campaign declined",
                                "supporting_evidence_ids": [],
                                "contradicting_evidence_ids": [],
                                "reason": "Tentative hypothesis",
                            },
                        )
                    ]
                )
            if len(items) == 2:
                return ModelResponse(
                    parts=[
                        ToolCallPart(
                            "update_hypothesis",
                            {
                                "hypothesis_id": items[-1][1]["id"],
                                "status": "REJECTED",
                                "supporting_evidence_ids": [],
                                "contradicting_evidence_ids": ids,
                                "reason": "Observed metrics contradict the hypothesis",
                                "confidence": "medium",
                            },
                        )
                    ]
                )
        if mode == "compare" and len(items) == 1 and self.case.fixtures.baseline:
            observation = items[0][1].get("observation", "")
            if "relativeDelta=-0.5" in observation:
                if self.case.fixtures.metric == "CTR":
                    return self.call(
                        "breakdown_metric",
                        {"metric": "CTR", "time_range": CURRENT, "dimension": "CHANNEL"},
                    )
                return self.call(
                    "get_attribution_breakdown", {"time_range": CURRENT, "dimension": "CHANNEL"}
                )
        diagnosed = self.case.expected_status == "DIAGNOSED"
        output = {
            "status": "DIAGNOSED" if diagnosed else "INSUFFICIENT_EVIDENCE",
            "summary": "Aggregate evidence reviewed; no causal conclusion is established.",
            "findings": [{"claim": "Observed aggregate facts", "evidence_ids": ids}]
            if diagnosed
            else [],
            "evidence_ids": ids,
            "unresolved_questions": [] if diagnosed else ["Business evidence gap"],
            "confidence": "medium" if diagnosed else "low",
            "recommended_next_action": None,
        }
        if info.output_tools:
            return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, output)])
        return ModelResponse(parts=[TextPart(json.dumps(output))])

    def call(self, name: str, request: dict[str, Any]) -> ModelResponse:
        return ModelResponse(parts=[ToolCallPart(name, {"request": request})])
