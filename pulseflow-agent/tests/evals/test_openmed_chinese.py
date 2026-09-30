"""Synthetic Chinese PII regression set; real weights are explicitly opt-in."""

import json
import os
from pathlib import Path
from time import perf_counter
from types import SimpleNamespace

import psutil
import pytest

from pulseflow_agent.security.pii_guardrail import OpenMedPiiGuardrail, PiiBlockedError
from tests.test_foundation import settings

CASES = json.loads((Path(__file__).parent / "pii_cases.json").read_text(encoding="utf-8"))


@pytest.mark.asyncio
@pytest.mark.parametrize("case", CASES, ids=lambda case: str(case["id"]))
async def test_synthetic_pii_guardrail_with_fake_detector(case: dict[str, object]) -> None:
    expected = case["expected"]
    assert isinstance(expected, list)
    guardrail = OpenMedPiiGuardrail(
        settings(real=True),
        lambda _: SimpleNamespace(entities=[SimpleNamespace(label=label) for label in expected]),
    )
    if expected:
        with pytest.raises(PiiBlockedError, match="pii_detected"):
            await guardrail.check(case["text"])
    else:
        await guardrail.check(case["text"])


@pytest.mark.skipif(
    os.getenv("REAL_OPENMED_PII_EVAL") != "true", reason="real OpenMed eval is opt-in"
)
def test_real_openmed_chinese_smoke() -> None:
    from openmed import ModelLoader, OpenMedConfig, extract_pii

    config = settings(real=True)
    cache = Path(os.path.expanduser(config.pulseflow_agent_pii_cache_dir))
    started = perf_counter()
    loader = ModelLoader(OpenMedConfig(
        cache_dir=str(cache), local_only=True
    ))
    loader.load_model(config.pulseflow_agent_pii_model)
    load_seconds = perf_counter() - started
    cache_bytes = sum(path.stat().st_size for path in cache.rglob("*") if path.is_file())
    rss_bytes = psutil.Process().memory_info().rss
    print(f"cache_bytes={cache_bytes} cold_load_seconds={load_seconds:.3f} rss_bytes={rss_bytes}")
    failures: list[str] = []
    for case in CASES:
        started = perf_counter()
        result = extract_pii(
            case["text"], lang="zh", model_name=config.pulseflow_agent_pii_model,
            loader=loader,
        )
        inference_seconds = perf_counter() - started
        actual = {
            "NAME" if entity.label == "PERSON" else entity.label for entity in result.entities
        }
        expected = set(case["expected"])
        passed = expected.issubset(actual) if expected else not actual
        print(
            f"case={case['id']} expected={sorted(expected)} "
            f"actual={sorted(actual)} pass={passed} inference_seconds={inference_seconds:.3f}"
        )
        if not passed:
            failures.append(str(case["id"]))
    assert not failures, f"OpenMed regression failures: {', '.join(failures)}"
