"""Synthetic Chinese PII regression set; real weights are explicitly opt-in."""

import asyncio
import json
import os
import platform
import statistics
import sys
import threading
from pathlib import Path
from time import perf_counter
from types import SimpleNamespace

import psutil
import pytest
from fastapi.testclient import TestClient
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from pulseflow_agent.main import create_app
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
def test_real_openmed_chinese_smoke(caplog: pytest.LogCaptureFixture) -> None:
    import torch
    from openmed import ModelLoader, OpenMedConfig, extract_pii

    config = settings(real=True)
    cache = Path(os.path.expanduser(config.pulseflow_agent_pii_cache_dir))
    started = perf_counter()
    loader = ModelLoader(OpenMedConfig(
        cache_dir=str(cache), local_only=True
    ))
    loader.load_model(config.pulseflow_agent_pii_model)
    load_seconds = perf_counter() - started
    cache_bytes = sum(
        path.stat().st_size for path in cache.rglob("*")
        if path.is_file() and not path.is_symlink()
    )
    rss_bytes = psutil.Process().memory_info().rss
    cpu_name = platform.processor()
    if Path("/proc/cpuinfo").exists():
        cpu_name = next(
            (line.partition(":")[2].strip() for line in Path("/proc/cpuinfo").read_text()
             .splitlines() if line.startswith("model name")),
            cpu_name,
        )
    print(
        f"environment={platform.platform()} cpu={cpu_name} python={sys.version.split()[0]} "
        f"logical_cpus={psutil.cpu_count()} ram_bytes={psutil.virtual_memory().total} "
        f"cuda={torch.cuda.is_available()}"
    )
    print(f"cache_bytes={cache_bytes} cold_load_seconds={load_seconds:.3f} rss_bytes={rss_bytes}")
    failures: list[str] = []
    first_inference_seconds: float | None = None
    clean_texts: list[str] = []
    for case in CASES:
        started = perf_counter()
        result = extract_pii(
            case["text"], lang="zh", model_name=config.pulseflow_agent_pii_model,
            loader=loader,
        )
        inference_seconds = perf_counter() - started
        if first_inference_seconds is None:
            first_inference_seconds = inference_seconds
        assert isinstance(result.entities, list), type(result.entities).__name__
        print(f"result_entities_type={type(result.entities).__name__}")
        raw_labels = {str(entity.label).upper() for entity in result.entities}
        families = {
            "PERSON": "NAME", "FIRSTNAME": "NAME", "LASTNAME": "NAME",
            "LOCATION": "ADDRESS", "PHONE_NUMBER": "PHONE", "EMAIL_ADDRESS": "EMAIL",
            "IDENTIFIER": "ID", "NATIONAL_ID": "ID", "SOCIAL_CREDIT_CODE": "ID",
        }
        actual = {families.get(label, label) for label in raw_labels}
        expected = set(case["expected"])
        passed = expected.issubset(actual) if expected else not actual
        print(
            f"case={case['id']} expected={sorted(expected)} "
            f"raw={sorted(raw_labels)} actual={sorted(actual)} "
            f"pass={passed} inference_seconds={inference_seconds:.3f}"
        )
        if not passed:
            failures.append(str(case["id"]))
        if not expected and not actual:
            clean_texts.append(str(case["text"]))
    assert first_inference_seconds is not None
    print(f"first_inference_seconds={first_inference_seconds:.3f}")
    assert clean_texts, "No clean Campaign text available for runtime checks"
    warm_samples: list[float] = []
    for _ in range(7):
        started = perf_counter()
        extract_pii(
            clean_texts[0], lang="zh", model_name=config.pulseflow_agent_pii_model,
            loader=loader,
        )
        warm_samples.append(perf_counter() - started)
    print(f"warm_inference_median_seconds={statistics.median(warm_samples):.3f}")

    calls = 0
    active = 0
    max_active = 0
    mutex = threading.Lock()

    def detect(value: str) -> object:
        nonlocal calls, active, max_active
        with mutex:
            calls += 1
            active += 1
            max_active = max(max_active, active)
        try:
            return extract_pii(
                value, lang="zh", model_name=config.pulseflow_agent_pii_model,
                loader=loader,
            )
        finally:
            with mutex:
                active -= 1

    guardrail = OpenMedPiiGuardrail(config, detect)

    async def exercise_guardrail() -> None:
        for field in ("userId", "rawEvents", "orderDetails", "deviceId", "behaviourLogs"):
            before = calls
            with pytest.raises(PiiBlockedError, match="blocked_business_field"):
                await guardrail.check({field: "synthetic"})
            assert calls == before, field
        await guardrail.check(clean_texts[0])
        await asyncio.gather(*(guardrail.check(text) for text in clean_texts[:3]))
        ticker = asyncio.create_task(asyncio.sleep(0.02))
        pending = asyncio.create_task(guardrail.check(clean_texts[0]))
        await ticker
        assert not pending.done(), "Inference unexpectedly completed before event-loop probe"
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        await asyncio.wait_for(guardrail.check(clean_texts[0]), timeout=120)

    asyncio.run(exercise_guardrail())
    print(f"serialized_inference={max_active == 1} detector_calls={calls}")
    assert max_active == 1

    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    secrets = ("13800138000", "test@example.com", "110101199001010015", "虚构街100号", "王芳")
    log_guardrail = OpenMedPiiGuardrail(config, detect)
    errors: list[str] = []
    for case in CASES[:5]:
        try:
            asyncio.run(log_guardrail.check(case["text"]))
        except PiiBlockedError as error:
            errors.append(str(error))
    span_data = repr([span.attributes for span in exporter.get_finished_spans()])
    log_data = "\n".join(record.getMessage() for record in caplog.records)
    assert all(secret not in log_data for secret in secrets)
    assert all(secret not in span_data for secret in secrets)
    assert all(secret not in " ".join(errors) for secret in secrets)
    print("raw_pii_in_logs=False raw_pii_in_trace=False raw_pii_in_exception=False")
    assert not failures, f"OpenMed regression failures: {', '.join(failures)}"


@pytest.mark.skipif(
    os.getenv("REAL_OPENMED_PII_EVAL") != "true", reason="real OpenMed eval is opt-in"
)
def test_real_openmed_missing_cache_is_not_ready(tmp_path: Path) -> None:
    config = settings(real=True, pulseflow_agent_pii_cache_dir=str(tmp_path))
    app = create_app(config)
    with pytest.raises(PiiBlockedError, match="pii_provider_unavailable"):
        with TestClient(app):
            pass
    assert app.state.ready is False
