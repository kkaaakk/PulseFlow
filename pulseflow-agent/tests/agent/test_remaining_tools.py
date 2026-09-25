"""Exercise the other three Java tool routes through the actual Agent loop."""

import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr
from pydantic_ai import ModelMessage, ModelResponse, TextPart, ToolCallPart, ToolReturnPart
from pydantic_ai.models.function import AgentInfo, FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.investigation import ToolObservation
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail

RANGE = {"fromInclusive": "2026-09-01T00:00:00+08:00",
         "toExclusive": "2026-09-08T00:00:00+08:00"}
DSL = {
    "schema_version": 1, "campaign_name": "Safe preview", "objective": "RETENTION",
    "audience": {"logic": "AND", "conditions": [
        {"field": "activeDays7d", "operator": "GTE", "value": 5, "value_type": "INTEGER"}
    ]},
    "channel": "PUSH",
    "schedule": {"type": "ONCE", "send_at": "2027-01-01T10:00:00+08:00",
                 "timezone": "Asia/Shanghai"},
    "frequency_cap": {"max_times": 1, "window_hours": 24},
    "promotion_facts": [],
}


def meta(source: str, data_version: str | None = None) -> dict[str, object]:
    return {"queryId": str(uuid4()), "generatedAt": "2026-09-25T00:00:00Z",
            "dataVersion": data_version, "source": source, "warnings": []}


def tool_results(messages: list[ModelMessage]) -> list[ToolObservation]:
    items: list[ToolObservation] = []
    for message in messages:
        for part in message.parts:
            if isinstance(part, ToolReturnPart):
                if isinstance(part.content, str):
                    items.append(ToolObservation.model_validate_json(part.content))
                else:
                    items.append(ToolObservation.model_validate(part.content))
    return items


async def sequence(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
    results = tool_results(messages)
    if len(results) == 0:
        return ModelResponse(parts=[ToolCallPart("query_metric", {"request": {
            "metric": "CTR", "time_range": RANGE,
        }})])
    if len(results) == 1:
        return ModelResponse(parts=[ToolCallPart("get_campaign_performance", {"campaign_id": 9})])
    if len(results) == 2:
        return ModelResponse(parts=[ToolCallPart("preview_audience", {"request": {"dsl": DSL}})])
    ids = [item.evidence_id for item in results]
    diagnosis = {
        "status": "DIAGNOSED", "summary": "Three aggregate facts collected.",
        "findings": [{"claim": "Aggregate facts are available", "evidence_ids": ids}],
        "evidence_ids": ids, "unresolved_questions": [], "confidence": "medium",
        "recommended_next_action": None,
    }
    if info.output_tools:
        return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, diagnosis)])
    return ModelResponse(parts=[TextPart(json.dumps(diagnosis))])


@pytest.mark.asyncio
async def test_query_performance_and_preview_create_sanitized_evidence() -> None:
    config = AgentSettings.model_validate({
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("test-internal-token"),
    })
    paths: list[str] = []

    def java(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        if request.url.path.endswith("/metrics/query"):
            return httpx.Response(200, json={
                "metadata": meta("campaign-facts"), "metric": "CTR", "timeRange": RANGE,
                "dimensions": [], "rows": [{"dimensions": {}, "value": "0.2000",
                                         "sampleSize": 100}], "sampleSize": 100,
            })
        if request.url.path.endswith("/campaigns/9/performance"):
            return httpx.Response(200, json={
                "metadata": meta("campaign-summary", "campaign-summary:v1"),
                "campaignId": 9, "available": True, "targetAudienceCount": 100,
                "sentCount": 100, "deliveredCount": 90, "clickedCount": 18,
                "convertedCount": 4, "deliveryRate": "0.9000", "clickRate": "0.2000",
                "conversionRate": "0.2222", "calculatedAt": "2026-09-25T00:00:00Z",
            })
        if request.url.path.endswith("/audience/preview"):
            return httpx.Response(200, json={
                "metadata": meta("audience-preview", "profile-v1"),
                "estimatedCount": 42,
                "validation": {"valid": True, "needsConfirmation": False,
                               "errors": [], "missingFields": []},
            })
        raise AssertionError("Unexpected Java path")

    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        result = await GrowthInvestigator(
            config, AzurePiiGuardrail(config, http), PulseFlowApiClient(config, http),
            model=FunctionModel(sequence),
        ).run("调查安全的聚合指标")
    assert result.tool_trajectory == [
        "query_metric", "get_campaign_performance", "preview_audience"
    ]
    assert all(path.startswith("/internal/v1/agent-tools/") for path in paths)
    assert result.evidence[1].query == {"campaignId": 9}
    assert result.evidence[2].query == {
        "audience_conditions": 1, "channel": "PUSH", "objective": "RETENTION"
    }
    assert "campaign_name" not in str(result.evidence[2].query)
    assert len(result.evidence) == 3
