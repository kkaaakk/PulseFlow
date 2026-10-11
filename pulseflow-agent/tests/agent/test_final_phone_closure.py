"""Complete contact labels versus identifier fragments, through real check()."""

from unittest.mock import AsyncMock

import httpx
import pytest
from pydantic_ai import ModelResponse
from pydantic_ai.models.function import FunctionModel

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.security.structured_pii import StructuredPiiDetector
from tests.agent.test_pii_hardening import entity
from tests.test_foundation import azure_response, settings

POSITIVE = [
    "Contact - 13800138000",
    "Contact: 13800138000",
    "Contact-13800138000",
    "Please CONTACT - +86 138 0013 8000",
    "contact\u00a0—\u00a0138-0013-8000",
    "Contact\n13800138000",
    "Contact\u2003：\u200313800138000",
    "Contact number - 13800138000",
    "Tel: +86 138 0013 8000",
    "Telephone ‑ 13800138000",
    "Call\r\n138 0013 8000",
    "联系电话：13800138000",
]
NEGATIVE = [
    "NOTEL - 13800138000 - STAGE",
    "FORECALL - 13800138000",
    "XContact - 13800138000",
    "CallBack - 13800138000",
    "Q7_TEL - 13800138000",
    "Q7-CALL - 13800138000",
    "Q7‐Tel - 13800138000",
    "Q7\u200bTel - 13800138000",
    "编号甲Tel - 13800138000",
    "ORDER - Contact - 13800138000",
    "ORDER – Call – 13800138000",
    "ORDER _ Tel _ 13800138000",
]


@pytest.mark.parametrize("text", POSITIVE)
async def test_complete_contact_labels_still_block_without_azure_hit(text: str) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=azure_response()))
    ) as http:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await AzurePiiGuardrail(settings(real=True), http).check(text)


@pytest.mark.parametrize("text", NEGATIVE)
async def test_label_fragments_inside_codes_do_not_trust_azure_phone_claim(text: str) -> None:
    assert not StructuredPiiDetector().detect(text)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=azure_response([entity("PhoneNumber", text)]))
        )
    ) as http:
        await AzurePiiGuardrail(settings(real=True), http).check(text)


async def test_contact_prompt_stops_actual_agent_before_model(
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
        mock_model = AsyncMock()
        monkeypatch.setattr(investigator._agent, "run", mock_model)
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await investigator.run("Contact - 13800138000")
        mock_model.assert_not_awaited()
