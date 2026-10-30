"""A diagnosis cannot invent evidence; PII never reaches a subsequent model call."""

import json
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr
from pydantic_ai import ModelMessage, ModelResponse, TextPart, ToolCallPart, ToolReturnPart
from pydantic_ai.exceptions import UnexpectedModelBehavior
from pydantic_ai.models.function import AgentInfo, FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError


# 创建隔离数据库、内部令牌和离线模型配置，供安全测试复用。
def settings() -> AgentSettings:
    return AgentSettings.model_validate({
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_internal_token": SecretStr("test-internal-token"),
        "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
    })


# 构造带证据引用的最终诊断模型响应。
def final_response(info: AgentInfo, diagnosis: dict[str, object]) -> ModelResponse:
    if info.output_tools:
        return ModelResponse(parts=[ToolCallPart(info.output_tools[0].name, diagnosis)])
    return ModelResponse(parts=[TextPart(json.dumps(diagnosis))])


# 验证模型捏造的证据 ID 不能通过输出校验。
@pytest.mark.asyncio
async def test_invalid_evidence_reference_is_not_accepted() -> None:
    calls = 0

    # 模拟引用不存在证据的模型输出。
    async def invented(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        false_id = str(uuid4())
        return final_response(info, {
            "status": "DIAGNOSED", "summary": "Invented conclusion",
            "findings": [{"claim": "Unsupported claim", "evidence_ids": [false_id]}],
            "evidence_ids": [false_id], "unresolved_questions": [],
            "confidence": "high", "recommended_next_action": None,
        })

    config = settings()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: pytest.fail("Java should not be called")
    )) as http:
        investigator = GrowthInvestigator(config, AzurePiiGuardrail(config, http),
                                          PulseFlowApiClient(config, http),
                                          model=FunctionModel(invented))
        with pytest.raises(UnexpectedModelBehavior):
            await investigator.run("调查 Campaign 转化")
    assert calls >= 2  # the validator rejected the first output and requested a retry


# 验证恶意 Java 维度值在下一次模型请求前被阻断。
@pytest.mark.asyncio
async def test_malicious_java_dimension_is_blocked_before_next_model_request() -> None:
    model_calls = 0

    # 模拟先请求一次业务指标再尝试继续的模型流程。
    async def query_once(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        nonlocal model_calls
        model_calls += 1
        if model_calls > 1:
            pytest.fail("PII-containing tool output reached another model request")
        return ModelResponse(parts=[ToolCallPart("query_metric", {"request": {
            "metric": "CTR",
            "time_range": {
                "fromInclusive": "2026-09-01T00:00:00+08:00",
                "toExclusive": "2026-09-08T00:00:00+08:00",
            },
            "dimensions": ["CHANNEL"],
        }})])

    # 提供带恶意维度文本的 Java HTTP 响应替身。
    def java(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "metadata": {"queryId": str(uuid4()), "generatedAt": "2026-09-25T00:00:00Z",
                         "dataVersion": None, "source": "campaign-facts", "warnings": []},
            "metric": "CTR",
            "timeRange": {"fromInclusive": "2026-09-01T00:00:00+08:00",
                          "toExclusive": "2026-09-08T00:00:00+08:00"},
            "dimensions": ["CHANNEL"],
            "rows": [{"dimensions": {"CHANNEL": "userId 123456"},
                      "value": "0.1000", "sampleSize": 10}],
            "sampleSize": 10,
        })

    config = settings()
    async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as http:
        investigator = GrowthInvestigator(config, AzurePiiGuardrail(config, http),
                                          PulseFlowApiClient(config, http),
                                          model=FunctionModel(query_once))
        with pytest.raises(PiiBlockedError) as error:
            await investigator.run("调查 Campaign 点击率")
    assert model_calls == 1
    assert "123456" not in str(error.value)


# 验证 Java 工具不可用时可以诚实结束为证据不足。
@pytest.mark.asyncio
async def test_unavailable_java_tool_can_end_with_insufficient_evidence() -> None:
    # 模拟收到工具失败后输出低置信度证据不足结论。
    async def stop_on_failure(messages: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        failures = [part for message in messages for part in message.parts
                    if isinstance(part, ToolReturnPart)]
        if not failures:
            return ModelResponse(parts=[ToolCallPart("query_metric", {"request": {
                "metric": "CTR", "time_range": {
                    "fromInclusive": "2026-09-01T00:00:00+08:00",
                    "toExclusive": "2026-09-08T00:00:00+08:00",
                },
            }})])
        return final_response(info, {
            "status": "INSUFFICIENT_EVIDENCE",
            "summary": "The business tool is unavailable.",
            "findings": [], "evidence_ids": [],
            "unresolved_questions": ["What does the Java metric show?"],
            "confidence": "low", "recommended_next_action": None,
        })

    config = settings()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(503, text="internal SQL should stay private")
    )) as http:
        result = await GrowthInvestigator(
            config, AzurePiiGuardrail(config, http), PulseFlowApiClient(config, http),
            model=FunctionModel(stop_on_failure),
        ).run("调查 Campaign 点击率")
    assert result.diagnosis.status == "INSUFFICIENT_EVIDENCE"
    assert result.evidence == []
    assert result.tool_trajectory == ["query_metric"]
    assert "internal SQL" not in result.model_dump_json()
