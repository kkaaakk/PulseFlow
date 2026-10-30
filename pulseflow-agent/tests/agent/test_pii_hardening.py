"""Exercise the production guardrail with synthetic data and mocked Azure/LLM I/O."""

import json
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import phonenumbers
import pytest
from pydantic_ai import ModelMessage, ModelResponse, ToolCallPart
from pydantic_ai.models.function import AgentInfo, FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.security.pii_entities import AZURE_TYPES, blocking_entities
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.security.structured_pii import StructuredPiiDetector
from tests.agent.test_safety import final_response
from tests.test_foundation import azure_response, settings

HISTORICAL = json.loads(
    (Path(__file__).parents[1] / "fixtures/invalid_dotted_ip.json").read_text(encoding="utf-8")
)["cases"]
VALID = [
    ("13800138000", "PHONE", "PhoneNumber"),
    ("+86 138 0013 8000", "PHONE", "PhoneNumber"),
    ("138 0013 8000", "PHONE", "PhoneNumber"),
    ("138-0013-8000", "PHONE", "PhoneNumber"),
    ("联系电话:13800138000", "PHONE", "PhoneNumber"),
    ("+1 202-555-0123", "PHONE", "PhoneNumber"),
    ("110101199001018009", "GOVERNMENT_ID_CN", "CNResidentIdentityCardNumber"),
    ("110101199001018009", "GOVERNMENT_ID_CN", "CNResidentId"),
    ("4111 1111 1111 1111", "BANK_CARD", "CreditCardNumber"),
    ("4111-1111-1111-1111", "BANK_CARD", "CreditCardNumber"),
]
NEGATIVE = [c["text"] for c in HISTORICAL] + [
    "网络 192.0.2.1 和 198.51.100.42",
    "IPv6 2001:db8::1 和 ::ffff:192.0.2.1",
    "IPv6 2001:db8:138:0013:8000::1",
    "版本 1.38.0013.8000",
    "金额 198.51，预算 1380013.80 元",
    "活动 ID campaign-202610100001",
    "订单号 ORD20261010138001380001234",
    "长数字 99999999999999999999999999999",
    "身份证号 110101199001018000",  # wrong checksum
    "卡号 4111111111111112",  # wrong checksum
    "手机号字段为空；银行卡绑定率；身份证数据质量；年龄段统计",
    "分析国庆期间广州地区的 CTR",
    "550e8400-e29b-41d4-a716-446655440000",
    "138.0013.8000",  # documented policy boundary: ambiguous dotted notation
]


# 构造带测试区间、标签与置信度的模拟 PII 实体。
def entity(category: str, text: str, start: int = 0) -> dict[str, object]:
    return {"category": category, "offset": start, "length": len(text), "confidenceScore": 0.99}


# 验证非核心标签通过真实守卫组合流程。
@pytest.mark.parametrize(
    "category",
    [
        "Organization",
        "PersonType",
        "DateTime",
        "Location",
        "Age",
        "Quantity",
        "URL",
        "BankAccountNumber",
        "CNBankAccountNumber",
        "InternationalBankingAccountNumber",
    ],
)
async def test_non_core_labels_pass_real_guardrail(category: str) -> None:
    text = "分析国庆期间广州地区的 CTR"
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity(category, text)]))
        )
    ) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证核心实体规范化及阻断策略。
@pytest.mark.parametrize(
    ("text", "family", "label"),
    VALID
    + [
        ("张三", "NAME", "Person"),
        ("测试路一号二栋三室", "POSTAL_ADDRESS", "Address"),
        ("synthetic@example.invalid", "EMAIL", "Email"),
    ],
)
async def test_core_normalization_and_block(text: str, family: str, label: str) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity(label, text)]))
        )
    ) as http:
        guard = AzurePiiGuardrail(settings(real=True), http)
        detected = await guard._detect_azure(text)
        assert detected[0].normalized_family == family
        assert detected[0].raw_label == label
        if family == "POSTAL_ADDRESS":
            await guard.check(text)
            assert blocking_entities(detected, guard._structured.detect(text)) == []
            return
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await guard.check(text)
        merged = blocking_entities(detected, guard._structured.detect(text))
        assert len(merged) == 1
        assert "azure" in merged[0].sources


# 验证本地结构验证可以补足 Azure 漏检。
@pytest.mark.parametrize(("text", "family", "_label"), VALID)
async def test_azure_miss_is_filled_locally(text: str, family: str, _label: str) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        guard = AzurePiiGuardrail(settings(real=True), http)
        assert guard._structured.detect(text)[0].normalized_family == family
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await guard.check(text)


# 验证缺乏本地有效性支持的 Azure 电话误报不阻断近似负样本。
@pytest.mark.parametrize("text", NEGATIVE)
async def test_near_negatives_pass_even_with_unvalidated_azure_phone(text: str) -> None:
    assert StructuredPiiDetector().detect(text) == []
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("PhoneNumber", text)]))
        )
    ) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证历史 IP 误报及严格电话分组规则不再回归。
@pytest.mark.parametrize("case", HISTORICAL, ids=[c["id"] for c in HISTORICAL])
def test_historical_ip_root_cause_and_strict_grouping(case: dict[str, object]) -> None:
    text = str(case["text"])
    old = list(phonenumbers.PhoneNumberMatcher(text, "CN"))
    assert len(old) == 1 and phonenumbers.is_valid_number(old[0].number)
    assert phonenumbers.region_code_for_number(old[0].number) == "CN"
    assert not list(
        phonenumbers.PhoneNumberMatcher(text, "CN", leniency=phonenumbers.Leniency.STRICT_GROUPING)
    )
    assert StructuredPiiDetector().detect(text) == []


# 验证无效的 Azure 结构实体声明不会被直接信任。
@pytest.mark.parametrize("label", ["CNResidentIdentityCardNumber", "CreditCardNumber"])
async def test_invalid_azure_structured_claim_is_not_trusted(label: str) -> None:
    text = "110101199001018000" if label.startswith("CN") else "4111111111111112"
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity(label, text)]))
        )
    ) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)


# 验证 Azure HTTP 故障按拒绝继续的策略处理。
@pytest.mark.parametrize("status", [401, 403, 408, 429, 500, 504])
async def test_http_failures_are_closed(status: int) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(status, text="synthetic provider failure")
        )
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_provider_unavailable"):
            await AzurePiiGuardrail(settings(real=True), http).check("正常运营文本")


# 验证畸形实体响应无法绕过安全校验。
@pytest.mark.parametrize(
    "record",
    [
        None,
        {},
        {"category": "Person"},
        {**entity("Person", "张三"), "offset": -1},
        {**entity("Person", "张三"), "length": 200},
        {**entity("Person", "张三"), "confidenceScore": "0.99"},
        {**entity("Person", "张三"), "confidenceScore": 1.1},
        {**entity("Person", "张三"), "offset": True},
    ],
)
async def test_malformed_entities_are_closed(record: object) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json=azure_response([record]),  # type: ignore[list-item]
            )
        )
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_provider_unavailable"):
            await AzurePiiGuardrail(settings(real=True), http).check("张三")


# 验证本地校验器异常时调查停止，不放行未经验证的内容。
async def test_local_validator_failure_is_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    # 模拟本地结构检测器故障，驱动守卫失败分支。
    def failure(_: str) -> list[object]:
        raise RuntimeError("do not expose text or credentials")

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("Person", "张三")]))
        )
    ) as http:
        guard = AzurePiiGuardrail(settings(real=True), http)
        monkeypatch.setattr(guard._structured, "detect", failure)
        with pytest.raises(PiiBlockedError, match="pii_validator_unavailable") as error:
            await guard.check("张三")
        assert "张三" not in str(error.value) and "credentials" not in str(error.value)


# 验证缺凭据或非法 JSON 响应时守卫拒绝继续。
async def test_missing_credentials_and_invalid_json_are_closed() -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, text="{malformed"))
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_provider_unavailable"):
            await AzurePiiGuardrail(settings(real=True), http).check("正常文本")
        for missing in ("azure_language_key", "azure_language_endpoint"):
            config = settings(real=True).model_copy(update={missing: None})
            with pytest.raises(PiiBlockedError, match="pii_provider_unavailable"):
                await AzurePiiGuardrail(config, http).check("正常文本")


# 验证实体区间采用 Unicode 码点，并核对 Azure 请求契约。
async def test_unicode_code_points_and_request_contract() -> None:
    # 生成带 Unicode 边界实体的模拟 Azure 响应。
    def response(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert request.url.params["api-version"] == "2024-11-01"
        assert request.url.path == "/language/:analyze-text"
        assert body["parameters"] == {
            "modelVersion": "latest",
            "loggingOptOut": True,
            "stringIndexType": "UnicodeCodePoint",
        }
        assert request.extensions["timeout"]["read"] == 5.0
        return httpx.Response(200, json=azure_response([entity("Person", "张三", start=1)]))

    async with httpx.AsyncClient(transport=httpx.MockTransport(response)) as http:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await AzurePiiGuardrail(settings(real=True), http).check("😀张三")


# 验证含 PII 的真实 Agent 输入在模型调用前被阻断。
@pytest.mark.parametrize("text", ["13800138000", "110101199001018009", "4111111111111111"])
async def test_actual_agent_prompt_blocks_before_model(
    text: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = settings(real=True)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        investigator = GrowthInvestigator(
            config,
            AzurePiiGuardrail(config, http),
            PulseFlowApiClient(config, http),
            model=FunctionModel(lambda _m, _i: ModelResponse(parts=[])),
        )
        model_run = AsyncMock()
        monkeypatch.setattr(investigator._agent, "run", model_run)
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await investigator.run(text)
        model_run.assert_not_awaited()


# 验证安全聚合业务问题能够进入模拟模型。
async def test_actual_agent_business_text_reaches_mock_model() -> None:
    calls = 0

    # 模拟接收安全问题的模型并记录调用情况。
    async def model(_: list[ModelMessage], info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        return final_response(
            info,
            {
                "status": "INSUFFICIENT_EVIDENCE",
                "summary": "缺少业务证据",
                "findings": [],
                "evidence_ids": [],
                "unresolved_questions": ["业务指标是什么？"],
                "confidence": "low",
                "recommended_next_action": None,
            },
        )

    config = settings(real=True)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("DateTime", "日")]))
        )
    ) as http:
        result = await GrowthInvestigator(
            config,
            AzurePiiGuardrail(config, http),
            PulseFlowApiClient(config, http),
            model=FunctionModel(model),
        ).run("分析国庆期间广州地区的 CTR")
        assert result.diagnosis.status == "INSUFFICIENT_EVIDENCE" and calls == 1


# 验证 Java 工具响应中的 PII 不会进入下一次模型请求。
async def test_actual_java_tool_pii_cannot_reach_next_model() -> None:
    calls = 0

    # 驱动工具调用后的模型步骤，供断言危险工具结果被提前拦截。
    async def model(_: list[ModelMessage], _info: AgentInfo) -> ModelResponse:
        nonlocal calls
        calls += 1
        assert calls == 1, "PII reached the next model request"
        return ModelResponse(
            parts=[
                ToolCallPart(
                    "query_metric",
                    {
                        "request": {
                            "metric": "CTR",
                            "time_range": {
                                "fromInclusive": "2026-09-01T00:00:00+08:00",
                                "toExclusive": "2026-09-08T00:00:00+08:00",
                            },
                            "dimensions": ["CHANNEL"],
                        }
                    },
                )
            ]
        )

    # 为工具安全测试提供可注入危险维度值的 HTTP 传输替身。
    def transport(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/language/:analyze-text":
            return httpx.Response(200, json=azure_response())
        return httpx.Response(
            200,
            json={
                "metadata": {
                    "queryId": str(uuid4()),
                    "generatedAt": "2026-09-25T00:00:00Z",
                    "dataVersion": None,
                    "source": "campaign-facts",
                    "warnings": [],
                },
                "metric": "CTR",
                "timeRange": {
                    "fromInclusive": "2026-09-01T00:00:00+08:00",
                    "toExclusive": "2026-09-08T00:00:00+08:00",
                },
                "dimensions": ["CHANNEL"],
                "rows": [
                    {"dimensions": {"CHANNEL": "13800138000"}, "value": "0.1000", "sampleSize": 10}
                ],
                "sampleSize": 10,
            },
        )

    config = settings(real=True)
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
        with pytest.raises(PiiBlockedError, match="blocked_tool_value"):
            await GrowthInvestigator(
                config,
                AzurePiiGuardrail(config, http),
                PulseFlowApiClient(config, http),
                model=FunctionModel(model),
            ).run("调查 Campaign 点击率")
        assert calls == 1


# 验证实体映射范围受限，结构化检测不加载 NLP 模型。
def test_mapping_is_narrow_and_no_model_is_loaded(monkeypatch: pytest.MonkeyPatch) -> None:
    import spacy

    monkeypatch.setattr(spacy, "load", lambda *_a, **_kw: pytest.fail("No NER model allowed"))
    assert StructuredPiiDetector().detect("手机号字段为空") == []
    assert "Location" not in AZURE_TYPES and "URL" not in AZURE_TYPES


# 验证 Azure 重复实体被合并而不重复计数。
def test_duplicate_azure_entities_do_not_repeat_counts() -> None:
    from pulseflow_agent.security.pii_guardrail import _parse_entities

    for text, label in [("张三", "Person"), ("13800138000", "PhoneNumber")]:
        azure = _parse_entities([entity(label, text)] * 2, text)
        combined = blocking_entities(azure, StructuredPiiDetector().detect(text))
        assert len(combined) == 1
        assert "azure" in combined[0].sources


# 验证已开启 HTTP instrumentation 时 Azure 请求仍不导出端点或原始输入。
async def test_instrumented_azure_call_does_not_export_endpoint_or_input() -> None:
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

    from pulseflow_agent.observability.tracing import Telemetry

    exporter = InMemorySpanExporter()
    telemetry = Telemetry(exporter=exporter)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        HTTPXClientInstrumentor.instrument_client(http, tracer_provider=telemetry.provider)
        await http.get("https://synthetic-probe.invalid")
        assert exporter.get_finished_spans()  # prove the shared client is instrumented
        exporter.clear()
        await AzurePiiGuardrail(settings(real=True), http).check("PRIVATE_INPUT_SENTINEL")
        assert not exporter.get_finished_spans()
    telemetry.shutdown()
