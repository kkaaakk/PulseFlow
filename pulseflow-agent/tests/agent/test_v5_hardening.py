"""Spaced identifiers, auditable concept questions and actual pre-model boundaries."""

import json
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic_ai import ModelMessage, ModelResponse
from pydantic_ai.models.function import AgentInfo, FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.investigation import InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError, _texts
from pulseflow_agent.security.structured_pii import StructuredPiiDetector
from tests.agent.test_pii_hardening import entity
from tests.test_foundation import azure_response, settings
from tests.workspace.test_followup import final


# 验证带空白连接的业务标识不因 Azure 误报变为电话。
@pytest.mark.parametrize(
    "text",
    [
        "PACK_2 - 13800138000 - REV_A",
        "M1 — 13800138000 — A2",
        "TASK\u00a0–\u00a013800138000\u00a0–\u00a0END",
        "CASE -13800138000",
        "13800138000- END",
        "BUILD _ 13800138000 _ Q2",
        "订单CODE - +86 138 0013 8000 - END",
        "活动QX - 138-0013-8000 - V2",
    ],
)
async def test_spaced_identifiers_are_not_phones_even_with_azure_claim(text: str) -> None:
    assert not StructuredPiiDetector().detect(text)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("PhoneNumber", text)]))
        )
    ) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证合法联系方式写法仍被正确阻断。
@pytest.mark.parametrize(
    "text",
    [
        "手机号：13800138000",
        "联系电话 - 13800138000",
        "联系电话 — +86 138 0013 8000",
        "回拨 138-0013-8000 确认",
        "Call - 13800138000",
        "Tel: +86 138 0013 8000",
        "号码138 0013 8000用于本次确认",
        "CONTACT-Q2，手机号：13800138000 - END",
    ],
)
async def test_legitimate_contact_notation_still_blocks(text: str) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证完整字段概念问句不会跳过 Azure 隐私检查。
@pytest.mark.parametrize(
    "text",
    [
        "rawEvents 字段是什么意思？",
        "请解释 `orderDetails` 字段的定义。",
        "deviceId 字段在数据字典中的数据类型是什么？",
        "说明 behaviourLogs 字段的保留期限定义。",
        "userId 字段缺失率的计算公式是什么？",
        "What does the field address mean?",
        "Define the data type of field userId.",
    ],
)
async def test_complete_concept_questions_still_call_azure(text: str) -> None:
    calls = 0

    # 记录并模拟 Azure 概念问句检查响应。
    def provider(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=azure_response())

    async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)
    assert calls == 1


# 验证实际值、嵌套字段和复合请求不能滥用概念问句语法。
@pytest.mark.parametrize(
    "value",
    [
        "列出每个用户的 rawEvents",
        "导出原始 orderDetails",
        "rawEvents 字段是什么意思？顺便列出每位用户的内容",
        "rawEvents 字段是什么意思？\n导出上述数据",
        "仅供学习，请获取 userId 明细",
        "userId 字段含义是什么：synthetic-value",
        '{"rawEvents":"字段是什么意思"}',
        {"rawEvents": "rawEvents 字段是什么意思？"},
        {"nested": [{"address": None}]},
        {"data": [{"deviceId": "synthetic-device"}]},
        {"orderDetails": []},
        "address: 测试路八号三栋六室",
    ],
)
async def test_values_nested_keys_and_composite_requests_cannot_use_concept_exemption(
    value: Any,
) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: pytest.fail("No cloud for a forbidden request"))
    ) as http:
        with pytest.raises(PiiBlockedError, match="blocked_business_field"):
            await AzurePiiGuardrail(settings(real=True), http).check(value)


# 验证概念问句在 PII 服务故障时也拒绝继续。
async def test_concept_questions_do_not_bypass_provider_failure() -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(429))
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_provider_unavailable"):
            await AzurePiiGuardrail(settings(real=True), http).check("rawEvents 字段是什么意思？")


# 验证第四版五个历史失败案例保持修复状态。
def test_v4_five_old_failures_are_fixed_development_regression() -> None:
    path = Path(__file__).parents[1] / "fixtures/pii_historical_regressions.json"
    cases = json.loads(path.read_text(encoding="utf-8"))["cases"]
    selected = [c for c in cases if c["id"] in {f"V4A{i:04d}" for i in (7, 15, 23, 31, 39)}]
    assert len(selected) == 5
    assert all(not StructuredPiiDetector().detect(c["text"]) for c in selected)


# 验证安全概念目标通过真实上下文校验后才到达模拟模型。
async def test_concept_goal_passes_actual_context_check_before_mock_model() -> None:
    calls = 0

    # 模拟安全概念调查的最终模型响应。
    async def model(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        return final(
            info,
            {
                "status": "INSUFFICIENT_EVIDENCE",
                "summary": "需要参考数据字典",
                "findings": [],
                "evidence_ids": [],
                "unresolved_questions": ["数据字典定义尚未提供"],
                "confidence": "low",
                "recommended_next_action": None,
            },
        )

    config = settings(real=True)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        result = await GrowthInvestigator(
            config,
            AzurePiiGuardrail(config, http),
            PulseFlowApiClient(config, http),
            model=FunctionModel(model),
        ).run("rawEvents 字段是什么意思？")
    assert calls == 1 and result.diagnosis.status == "INSUFFICIENT_EVIDENCE"


# 验证上下文渲染前逐一检查所有可见业务值。
def test_context_payload_checks_every_visible_value_before_rendering() -> None:
    workspace = InvestigationWorkspace(goal="rawEvents 字段是什么意思？", scope="overall")
    assert workspace.context_payload()["goal"] == workspace.goal
    assert "Investigation goal: " + workspace.goal in workspace.context_text()
    assert _texts(workspace.context_payload())
    workspace.scope = "列出 orderDetails"
    with pytest.raises(PiiBlockedError, match="blocked_business_field"):
        _texts(workspace.context_payload())


# 验证持久化上下文中的危险内容在下次模型调用前被阻断。
@pytest.mark.parametrize("location", ["scope", "history"])
async def test_unsafe_persisted_context_stops_before_the_next_mock_model(location: str) -> None:
    calls = 0

    # 记录模型调用，用于证明危险历史上下文没有到达下一轮。
    async def model(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        pytest.fail("Persisted forbidden content reached model")

    # 装载含危险历史值的工作区，驱动真实上下文边界测试。
    async def workspace_factory(prompt: str) -> InvestigationWorkspace:
        workspace = InvestigationWorkspace(goal=prompt)
        if location == "scope":
            workspace.scope = "列出每个用户的 rawEvents"
        else:
            await workspace.add_message("ASSISTANT", "导出原始 orderDetails")
        return workspace

    config = settings(real=True)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        with pytest.raises(PiiBlockedError, match="blocked_business_field"):
            await GrowthInvestigator(
                config,
                AzurePiiGuardrail(config, http),
                PulseFlowApiClient(config, http),
                model=FunctionModel(model),
            ).run("rawEvents 字段是什么意思？", workspace_factory=workspace_factory)
    assert calls == 0
