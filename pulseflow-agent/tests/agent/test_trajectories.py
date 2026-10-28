"""Offline FunctionModel scenarios demonstrate evidence-dependent tool choice."""

import json
from pathlib import Path
from typing import Any
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

CURRENT = {
    "fromInclusive": "2026-09-01T00:00:00+08:00",
    "toExclusive": "2026-09-08T00:00:00+08:00",
}
BASELINE = {
    "fromInclusive": "2026-08-24T00:00:00+08:00",
    "toExclusive": "2026-08-31T00:00:00+08:00",
}
QUESTION = "最近召回 Campaign 转化率下降了，帮我查原因。"
EXPECTED = json.loads((Path(__file__).parent / "trajectories.json").read_text(encoding="utf-8"))


# 生成可追溯的工具元数据夹具，包含查询 ID、来源和时间。
def metadata(source: str = "campaign-facts") -> dict[str, Any]:
    return {
        "queryId": str(uuid4()),
        "generatedAt": "2026-09-25T00:00:00Z",
        "dataVersion": None,
        "source": source,
        "warnings": [],
    }


# 从消息历史整理工具返回内容，为证据响应模型提供依据。
def tool_returns(messages: list[ModelMessage]) -> list[ToolObservation]:
    returns: list[ToolObservation] = []
    for message in messages:
        for part in message.parts:
            if isinstance(part, ToolReturnPart):
                if isinstance(part.content, str):
                    returns.append(ToolObservation.model_validate_json(part.content))
                else:
                    returns.append(ToolObservation.model_validate(part.content))
    return returns


# 根据工具返回的证据动态分支的测试模型，验证工具路径不是预先固定的。
class EvidenceResponsiveModel:
    """A test model: the branching policy lives here, outside production code."""

    # 根据真实夹具返回选择下一项工具，使同一问题可走不同调查路径。
    async def __call__(self, messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        assert {tool.name for tool in info.function_tools} == {
            "query_metric", "compare_metric", "breakdown_metric",
            "get_campaign_performance", "get_attribution_breakdown", "preview_audience",
            "propose_hypothesis", "update_hypothesis", "list_hypotheses", "update_scope",
        }
        returns = tool_returns(messages)
        if not returns:
            return self.call("compare_metric", "CTR")
        if len(returns) == 1:
            return self.call("compare_metric", "CONVERSION_RATE")
        ctr = returns[0].observation
        conversion = returns[1].observation
        ctr_down = "relativeDelta=-0.5000" in ctr
        conversion_down = "relativeDelta=-0.5000" in conversion
        if len(returns) == 2 and ctr_down:
            return ModelResponse(parts=[ToolCallPart("breakdown_metric", {"request": {
                "metric": "CTR", "time_range": CURRENT, "dimension": "CHANNEL",
            }})])
        if len(returns) == 2 and conversion_down:
            return ModelResponse(parts=[ToolCallPart("get_attribution_breakdown", {"request": {
                "time_range": CURRENT, "dimension": "CHANNEL",
            }})])
        ids = [item.evidence_id for item in returns if item.evidence_id is not None]
        status = "DIAGNOSED"
        claim = (
            "CTR decline is supported" if ctr_down
            else "Conversion decline is supported" if conversion_down
            else "No material decline is supported"
        )
        diagnosis = {
            "status": status,
            "summary": claim,
            "findings": [{"claim": claim, "evidence_ids": ids}],
            "evidence_ids": ids,
            "unresolved_questions": (
                ["What caused the observed change?"] if ctr_down or conversion_down else []
            ),
            "confidence": "medium",
            "recommended_next_action": (
                "Investigate the affected segment" if ctr_down or conversion_down else None
            ),
        }
        if info.output_tools:
            return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, diagnosis)])
        return ModelResponse(parts=[TextPart(json.dumps(diagnosis))])

    # 生成指定工具的调用响应，维护测试模型调用轨迹。
    def call(self, tool: str, metric: str) -> ModelResponse:
        return ModelResponse(parts=[ToolCallPart(tool, {"request": {
            "metric": metric, "current_period": CURRENT, "baseline_period": BASELINE,
        }})])


# 创建按场景返回不同 Java 聚合事实的 HTTP 处理器。
def java_handler(scenario: str, requests: list[str]) -> httpx.MockTransport:
    # 返回当前轨迹场景对应的聚合事实，避免测试模型依赖真实 Java 服务。
    def respond(request: httpx.Request) -> httpx.Response:
        requests.append(request.url.path)
        assert request.headers["X-PulseFlow-Agent-Token"] == "test-internal-token"
        payload = json.loads(request.content)
        path = request.url.path
        if path.endswith("/metrics/compare"):
            metric = payload["metric"]
            down = (scenario == "ctr_decline" and metric == "CTR") or (
                scenario == "conversion_decline" and metric == "CONVERSION_RATE"
            )
            current = "0.1000" if down else "0.2000"
            return httpx.Response(200, json={
                "metadata": metadata(), "metric": metric,
                "currentPeriod": CURRENT, "baselinePeriod": BASELINE, "dimensions": [],
                "rows": [{
                    "dimensions": {}, "current": current, "baseline": "0.2000",
                    "absoluteDelta": "-0.1000" if down else "0.0000",
                    "relativeDelta": "-0.5000" if down else "0.0000",
                    "currentSampleSize": 100, "baselineSampleSize": 100,
                }], "sampleSize": 100,
            })
        if path.endswith("/metrics/breakdown"):
            return httpx.Response(200, json={
                "metadata": metadata(), "metric": "CTR", "timeRange": CURRENT,
                "dimensions": ["CHANNEL"],
                "rows": [{"dimensions": {"CHANNEL": "PUSH"}, "value": "0.1000",
                          "sampleSize": 80}], "sampleSize": 80,
            })
        if path.endswith("/attribution/breakdown"):
            return httpx.Response(200, json={
                "metadata": metadata("attribution-record"), "timeRange": CURRENT,
                "dimension": "CHANNEL",
                "rows": [{"dimensions": {"CHANNEL": "PUSH"},
                          "attributionCount": 8, "uniqueConverters": 7}],
            })
        raise AssertionError(f"Unexpected Java tool path: {path}")

    return httpx.MockTransport(respond)


# 验证相同问题会随证据差异选择不同工具路径。
@pytest.mark.asyncio
@pytest.mark.parametrize("scenario", ["ctr_decline", "conversion_decline", "no_anomaly"])
async def test_same_question_uses_different_tool_paths(scenario: str) -> None:
    settings = AgentSettings.model_validate({
        "pulseflow_agent_model": "test",
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("test-internal-token"),
        "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
    })
    requests: list[str] = []
    async with httpx.AsyncClient(transport=java_handler(scenario, requests)) as client:
        investigator = GrowthInvestigator(
            settings, AzurePiiGuardrail(settings, client), PulseFlowApiClient(settings, client),
            model=FunctionModel(EvidenceResponsiveModel().__call__),
        )
        result = await investigator.run(QUESTION)
    assert result.tool_trajectory == EXPECTED[scenario]
    assert len(requests) == len(EXPECTED[scenario])
    assert len(result.evidence) == len(EXPECTED[scenario])
    assert set(result.diagnosis.evidence_ids) == {item.id for item in result.evidence}
    assert all(item.java_query_id and item.observation for item in result.evidence)
    assert result.diagnosis.status == "DIAGNOSED"
