"""Replay the final synthetic Azure observations through integrated check(), offline."""

import hashlib
import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from pulseflow_agent.security.pii_entities import blocking_entities
from pulseflow_agent.security.pii_guardrail import (
    AzurePiiGuardrail,
    PiiBlockedError,
    _parse_entities,
)
from tests.test_foundation import azure_response, settings

FIXTURE = json.loads(
    (Path(__file__).parents[1] / "fixtures/pii_final_saved_validation.json").read_text(
        encoding="utf-8"
    )
)
SAVED = FIXTURE["cases"]


# 验证保存的合成语料摘要保持封存值，避免回归数据被静默修改。
def test_saved_synthetic_corpus_keeps_original_sealed_hash() -> None:
    canonical = json.dumps(
        [row["case"] for row in SAVED], ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    assert len(SAVED) == 100
    assert hashlib.sha256(canonical).hexdigest() == FIXTURE["corpus_sha256"]


# 验证历史保存响应通过真实守卫重放仍满足预期。
@pytest.mark.parametrize("row", SAVED, ids=[row["case"]["id"] for row in SAVED])
async def test_final_saved_response_replays_through_actual_guardrail(row: dict[str, Any]) -> None:
    calls = 0
    entities = [
        {
            "category": e["raw_label"],
            "offset": e["start"],
            "length": e["end"] - e["start"],
            "confidenceScore": e["score"],
        }
        for e in row["azure_entities"]
    ]

    # 返回已保存的合成 Azure 响应，保证回归测试不依赖真实外部服务。
    def provider(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=azure_response(entities))

    async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as client:
        guard = AzurePiiGuardrail(settings(real=True), client)
        reason = "pass"
        try:
            await guard.check(row["case"]["text"])
        except PiiBlockedError as error:
            reason = error.reason
        assert reason == row["expected_reason"]
        assert calls == (0 if reason == "blocked_business_field" else 1)
        text = row["case"]["text"]
        actual = blocking_entities(_parse_entities(entities, text), guard._structured.detect(text))
        assert [(e.start, e.end, e.normalized_family) for e in actual] == [
            (e["start"], e["end"], e["normalized_family"]) for e in row["expected_policy"]
        ]
