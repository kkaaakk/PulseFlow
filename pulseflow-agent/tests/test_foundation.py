from unittest.mock import AsyncMock

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel, Field, SecretStr, ValidationError
from pydantic_ai.models.test import TestModel
from pydantic_ai.usage import UsageLimits

from pulseflow_agent.agent import growth_investigator
from pulseflow_agent.agent.growth_investigator import (
    GrowthInvestigator,
    create_model,
    create_usage_limits,
)
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.main import create_app
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError


# 生成基础 Agent 测试配置，支持按案例覆盖依赖设置。
def settings(real: bool = False, **overrides: object) -> AgentSettings:
    values: dict[str, object] = {
        "pulseflow_java_base_url": "http://localhost:8080",
        "pulseflow_agent_model": "openai:sample-model" if real else "test",
        "pulseflow_agent_env": "test",
        "pulseflow_agent_internal_token": SecretStr("fake-internal-token"),
        "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
    }
    if real:
        values.update(
            pulseflow_agent_api_key=SecretStr("fake-model-key"),
            azure_language_endpoint="https://example.cognitiveservices.azure.com",
            azure_language_key=SecretStr("fake-azure-key"),
        )
    values.update(overrides)
    return AgentSettings.model_validate(values)


# 构造 Azure PII 契约形状的模拟返回。
def azure_response(entities: list[dict[str, object]] | None = None) -> dict[str, object]:
    return {
        "kind": "PiiEntityRecognitionResults",
        "results": {"documents": [{"id": "1", "entities": entities or []}], "errors": []},
    }


# 验证离线 Agent 结构化输出与调用预算限制。
@pytest.mark.asyncio
async def test_offline_agent_structured_output_and_usage_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = settings()
    async with httpx.AsyncClient() as client:
        agent = GrowthInvestigator(
            config,
            AzurePiiGuardrail(config, client),
            PulseFlowApiClient(config, client),
            model=TestModel(
                call_tools=[],
                custom_output_args={
                    "status": "INSUFFICIENT_EVIDENCE",
                    "summary": "No business evidence yet.",
                    "findings": [],
                    "evidence_ids": [],
                    "unresolved_questions": ["What do the business metrics show?"],
                    "confidence": "low",
                    "recommended_next_action": None,
                },
            ),
        )
        assert create_model(config).model_name == "test"
        assert create_usage_limits(config).request_limit == 8
        result = await agent.run("Investigate growth")
        assert result.diagnosis.status == "INSUFFICIENT_EVIDENCE"
        assert result.evidence == []
        monkeypatch.setattr(
            growth_investigator, "create_usage_limits", lambda _: UsageLimits(request_limit=0)
        )
        limited = await agent.run("Investigate growth")
        assert limited.diagnosis.summary == "Investigation stopped at its usage budget."


# 验证受禁业务字段在 Azure 和模型调用之前即被阻断。
@pytest.mark.asyncio
async def test_local_fields_block_before_azure_or_model() -> None:
    requests: list[httpx.Request] = []

    # 记录不应被调用的 Azure 请求，证明本地字段边界先执行。
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=azure_response())

    config = settings(real=True)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        guardrail = AzurePiiGuardrail(config, client)
        blocked: list[object] = [
            {field: "secret"}
            for field in (
                "userId",
                "userIds",
                "mobile",
                "phone",
                "email",
                "address",
                "idCard",
                "idNumber",
                "deviceId",
                "imei",
                "rawEvents",
                "orderDetails",
                "behaviourLogs",
                "fullName",
                "realName",
            )
        ] + [
            {"nested": [{"rawEvents": [1]}]},
            "给 userId 123456 的用户发送优惠券",
            "USERID 123",
            "根据 orderDetails 推送",
        ]

        class Payload(BaseModel):
            nested: list[dict[str, str]]

        blocked.append(Payload(nested=[{"deviceId": "secret"}]))

        class AliasedPayload(BaseModel):
            identifier: str = Field(alias="userId")

        blocked.append(AliasedPayload(userId="secret"))
        for item in blocked:
            with pytest.raises(PiiBlockedError) as error:
                await guardrail.check(item)
            assert "123456" not in str(error.value)
        assert requests == []
        await guardrail.check("customerUserIdAlias")
        assert len(requests) == 1


# 验证 Azure 返回失败时守卫不放行内容。
@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("response", "reason"),
    [
        (httpx.Response(200, json=azure_response()), None),
        *[
            # Category-only records are malformed, not evidence of a valid PII span.
            (
                httpx.Response(200, json=azure_response([{"category": category}])),
                "pii_provider_unavailable",
            )
            for category in ("PhoneNumber", "Person", "Address", "Email", "BankAccountNumber")
        ],
        (httpx.Response(500), "pii_provider_unavailable"),
        (httpx.Response(200, json=None), "pii_provider_unavailable"),
        (httpx.Response(200, json={"results": None}), "pii_provider_unavailable"),
    ],
)
async def test_azure_fail_closed(response: httpx.Response, reason: str | None) -> None:
    config = settings(real=True)
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: response)) as client:
        guardrail = AzurePiiGuardrail(config, client)
        if reason is None:
            await guardrail.check("筛选最近7天活跃不少于5天的用户")
        else:
            with pytest.raises(PiiBlockedError) as error:
                await guardrail.check("给手机号13800138000的用户发送优惠")
            assert error.value.reason == reason
            assert "13800138000" not in str(error.value)


# 验证 Azure 超时拒绝继续，空输入按约定避免无意义调用。
@pytest.mark.asyncio
async def test_azure_timeout_and_blank_input() -> None:
    calls = 0

    # 为超时与空输入测试模拟 Azure 传输行为。
    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ReadTimeout("sensitive text", request=request)

    config = settings(real=True)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        guardrail = AzurePiiGuardrail(config, client)
        await guardrail.check("  ")
        assert calls == 0
        with pytest.raises(PiiBlockedError, match="pii_provider_unavailable") as error:
            await guardrail.check("普通活动文案")
        assert "sensitive" not in str(error.value)


# 验证被阻断的问题绝不会进入模型。
@pytest.mark.asyncio
async def test_blocked_prompt_never_reaches_model(monkeypatch: pytest.MonkeyPatch) -> None:
    config = settings()
    async with httpx.AsyncClient() as client:
        agent = GrowthInvestigator(
            config, AzurePiiGuardrail(config, client), PulseFlowApiClient(config, client)
        )
        model_run = AsyncMock()
        monkeypatch.setattr(agent._agent, "run", model_run)
        with pytest.raises(PiiBlockedError):
            await agent.run("分析 userId 123456 的行为")
        model_run.assert_not_awaited()


# 验证健康端点和真实模型配置缺失时的启动校验。
def test_health_and_invalid_real_configuration() -> None:
    with TestClient(create_app(settings())) as client:
        assert client.get("/health/live").json() == {"status": "live"}
        assert client.get("/health/ready").json() == {"status": "ready"}
    with pytest.raises(ValidationError):
        settings(real=True, azure_language_key=None)
    with pytest.raises(ValidationError):
        settings(pulseflow_agent_env="production")
    with pytest.raises(ValidationError):
        settings(real=True, azure_language_endpoint="http://example.com")
    with pytest.raises(ValueError, match="Java internal token"):
        with TestClient(create_app(settings(pulseflow_agent_internal_token=None))):
            pass


# 验证内部调查接口同时保留鉴权与 PII 的拒绝边界。
def test_internal_investigation_auth_and_pii_are_fail_closed() -> None:
    with TestClient(create_app(settings())) as client:
        path = "/internal/v1/investigations"
        assert client.post(path, content="not-json").status_code == 401
        assert (
            client.post(
                path,
                headers={"X-PulseFlow-Agent-Token": "wrong"},
                json={"question": "普通活动问题"},
            ).status_code
            == 401
        )
        blocked = client.post(
            path,
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
            json={"question": "分析 userId 123456 的行为"},
        )
        assert blocked.status_code == 422
        assert "123456" not in blocked.text
        blank = client.post(
            path,
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
            json={"question": "  "},
        )
        assert blank.status_code == 422
        extra = client.post(
            path,
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
            json={"question": "调查增长", "operatorId": "userId 123456"},
        )
        assert extra.status_code == 422
        assert "123456" not in extra.text
        offline = client.post(
            path,
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
            json={"question": "调查增长"},
        )
        assert offline.status_code == 200
        assert offline.json()["final_diagnosis"]["status"] == "INSUFFICIENT_EVIDENCE"
        assert offline.json()["tool_trajectory"] == []
        investigation_id = offline.json()["id"]
        loaded = client.get(
            f"{path}/{investigation_id}",
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
        )
        assert loaded.status_code == 200
        assert loaded.json()["id"] == investigation_id
        continued = client.post(
            f"{path}/{investigation_id}/follow-up",
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
            json={"question": "只看沉默30天以上用户", "scope": "沉默30天以上用户"},
        )
        assert continued.status_code == 200
        assert continued.json()["id"] == investigation_id
        assert continued.json()["scope_version"] == 1
        assert len(continued.json()["messages"]) == 4
        proposal = client.post(
            f"{path}/{investigation_id}/proposal",
            headers={"X-PulseFlow-Agent-Token": "fake-internal-token"},
            json={"question": "设计召回", "owner_id": 1024, "promotion_facts": []},
        )
        assert proposal.status_code == 409  # no supported Diagnosis in offline mode
