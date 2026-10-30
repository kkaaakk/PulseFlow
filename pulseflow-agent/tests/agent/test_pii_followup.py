"""Keep the strict business boundary and separate address recall from text blocks."""

import httpx
import pytest
from pydantic import BaseModel, Field

from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError, _texts
from tests.agent.test_pii_hardening import entity
from tests.test_foundation import azure_response, settings


# 验证未满足完整概念问句语法的文本仍阻断，完整问句继续接受隐私检查。
@pytest.mark.parametrize(
    "text",
    [
        "讨论userId字段缺失率，不读取任何用户记录",
        "rawEvents 仅是字段名",
        "orderDetails 字段含义是什么",
        "deviceId 是否有说明文档",
        "behaviourLogs 指什么",
    ],
)
async def test_unproven_concepts_remain_blocked_and_complete_questions_are_checked(
    text: str,
) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        guard = AzurePiiGuardrail(settings(real=True), http)
        if text == "orderDetails 字段含义是什么":
            await guard.check(text)  # Full metadata grammar, explicitly authorized in v5.
        else:
            with pytest.raises(PiiBlockedError, match="blocked_business_field"):
                await guard.check(text)


# 验证嵌套受禁字段即使值为空也不能绕过校验。
@pytest.mark.parametrize(
    "key", ["userId", "rawEvents", "orderDetails", "behaviourLogs", "deviceId"]
)
def test_empty_nested_forbidden_keys_remain_blocked(key: str) -> None:
    with pytest.raises(PiiBlockedError, match="blocked_business_field"):
        _texts({"tool": [{"result": {key: None}}]})


# 验证嵌套 Pydantic 序列化别名仍接受字段边界检查。
def test_nested_pydantic_alias_cannot_bypass_boundary() -> None:
    class Alias(BaseModel):
        identifier: str = Field(alias="deviceId")

    class Wrapper(BaseModel):
        records: list[Alias]

    with pytest.raises(PiiBlockedError, match="blocked_business_field"):
        _texts(Wrapper(records=[Alias(deviceId="synthetic-device")]))


# 验证地址和地区不会被错误重新归类为核心阻断实体。
@pytest.mark.parametrize(
    ("text", "label", "blocked"),
    [
        ("比较广州地区渠道表现", "Location", False),
        ("广东省用户活跃率", "Location", False),
        ("假定住址是南京市雨花台区栖鹭路317号6幢904室", "Address", False),
        ("住址为四川省成都市温江区银汀巷七十二号三栋五楼二室", "Address", False),
    ],
)
async def test_address_and_region_are_not_relabelled(
    text: str,
    label: str,
    blocked: bool,
) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity(label, text)]))
        )
    ) as http:
        guard = AzurePiiGuardrail(settings(real=True), http)
        if blocked:
            with pytest.raises(PiiBlockedError, match="pii_detected"):
                await guard.check(text)
        else:
            await guard.check(text)
