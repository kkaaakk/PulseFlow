"""Five-category scope, lexical phone boundaries and strict aggregate tool data."""

import json
from pathlib import Path

import httpx
import pytest

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.contracts import QueryResponse, ToolMetadata, UnsafeToolValueError
from pulseflow_agent.security.pii_entities import CORE_TYPES
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.security.structured_pii import StructuredPiiDetector
from tests.agent.test_pii_hardening import entity
from tests.test_foundation import azure_response, settings
from tests.tools.test_pulseflow_api import args, query_response

IDENTIFIERS = [
    "ACT-15160048012-QA",
    "ABC_18724068519_Z",
    "INV-+86-13800138000-END",
    "CAMP‑13800138000‑CHECK",
    "JOB-\u200b13800138000-TEST",
    "13800138000_SUFFIX",
    "prefix13800138000suffix",
    "run_+86_13800138000",
    "CODE-138-0013-8000-QA",
]


# 验证 Azure 误报电话时业务标识仍不被本地校验认定为电话。
@pytest.mark.parametrize("text", IDENTIFIERS)
async def test_business_identifiers_are_not_phones_even_when_azure_claims_phone(text: str) -> None:
    assert not StructuredPiiDetector().detect(text)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("PhoneNumber", text)]))
        )
    ) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证普通电话号码格式仍被安全边界阻断。
@pytest.mark.parametrize(
    "text",
    [
        "手机号：13800138000",
        "联系+86 138 0013 8000确认",
        "回拨(138-0013-8000)",
        "Call 13800138000 tomorrow",
        "号码138 0013 8000可以接听",
        "📞+86-138-0013-8000",
    ],
)
async def test_ordinary_phone_notation_is_still_blocked(text: str) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证仅五类实体触发实体阻断，同时保留受禁地址字段约束。
async def test_only_five_categories_block_and_address_keys_are_still_forbidden() -> None:
    assert CORE_TYPES == {"NAME", "PHONE", "EMAIL", "GOVERNMENT_ID_CN", "BANK_CARD"}
    text = "合成住处为测试路八号三栋六室"
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("Address", text)]))
        )
    ) as http:
        guard = AzurePiiGuardrail(settings(real=True), http)
        await guard.check(text)
        with pytest.raises(PiiBlockedError, match="blocked_business_field"):
            await guard.check({"nested": [{"address": text}]})


# 验证地址文本无法通过合法维度字段混入模型输入。
@pytest.mark.parametrize("dimension", ["CHANNEL", "CAMPAIGN", "DAY"])
async def test_tool_address_cannot_hide_in_an_allowed_dimension(dimension: str) -> None:
    payload = query_response()
    payload["dimensions"] = [dimension]
    payload["rows"] = [
        {"dimensions": {dimension: "测试路八号三栋六室"}, "value": "0.1", "sampleSize": 10}
    ]
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=payload))
    ) as http:
        with pytest.raises(PiiBlockedError, match="blocked_tool_value"):
            await PulseFlowApiClient(settings(), http).query_metric(args())


# 验证版本字段不能携带原始地址，同时合法聚合桶保持可用。
def test_tool_version_cannot_carry_raw_address_and_valid_buckets_still_work() -> None:
    meta = query_response()["metadata"]
    assert isinstance(meta, dict)
    with pytest.raises(UnsafeToolValueError, match="blocked_tool_value"):
        ToolMetadata.model_validate({**meta, "dataVersion": "测试路八号三栋六室"})
    for dimension, value in [("CHANNEL", "PUSH"), ("CAMPAIGN", "29"), ("DAY", "2026-10-10")]:
        payload = query_response()
        payload["rows"] = [{"dimensions": {dimension: value}, "value": "0.1", "sampleSize": 1}]
        assert QueryResponse.model_validate(payload).rows


# 验证历史第三版边界案例作为固定回归集保留。
def test_v3_boundary_cases_are_fixed_regression_not_new_holdout() -> None:
    path = Path(__file__).parents[1] / "fixtures/pii_historical_regressions.json"
    cases = json.loads(path.read_text(encoding="utf-8"))["cases"]
    selected = [c for c in cases if c["id"] in {"V3A0028", "V3A0036"}]
    assert len(selected) == 2
    assert all(not StructuredPiiDetector().detect(c["text"]) for c in selected)
