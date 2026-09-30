"""Fail-closed checks before any content is sent to a model."""

import asyncio
import os
import re
import threading
from collections.abc import Callable, Mapping
from typing import Any, Protocol

from opentelemetry import trace
from pydantic import BaseModel

from pulseflow_agent.config import AgentSettings

_BLOCKED_FIELDS = frozenset(
    {
        "userid", "userids", "mobile", "phone", "email", "address", "idcard",
        "idnumber", "deviceid", "imei", "rawevents", "orderdetails",
        "behaviourlogs", "fullname", "realname",
    }
)
_FIELD_MENTION = re.compile(
    r"(?<![A-Za-z0-9_])(?:" + "|".join(sorted(_BLOCKED_FIELDS, key=len, reverse=True))
    + r")(?![A-Za-z0-9_])",
    re.IGNORECASE,
)


class PiiBlockedError(Exception):
    """A deliberately content-free error safe for API and logs."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _texts(value: Any) -> list[str]:
    if isinstance(value, BaseModel):
        # Check both Python names and serialization aliases, including nested models.
        return _texts(value.model_dump(mode="json")) + _texts(
            value.model_dump(mode="json", by_alias=True)
        )
    if isinstance(value, Mapping):
        result: list[str] = []
        for key, child in value.items():
            if isinstance(key, str) and key.casefold() in _BLOCKED_FIELDS:
                raise PiiBlockedError("blocked_business_field")
            result.extend(_texts(child))
        return result
    if isinstance(value, (list, tuple)):
        return [text for child in value for text in _texts(child)]
    if isinstance(value, str):
        if _FIELD_MENTION.search(value):
            raise PiiBlockedError("blocked_business_field")
        return [value] if value.strip() else []
    return []


class PiiGuardrail(Protocol):
    async def check(self, content: Any) -> None: ...


class OpenMedPiiGuardrail:
    """One serialized, local OpenMed runtime shared by all agent runs."""

    def __init__(
        self, settings: AgentSettings, detector: Callable[[str], object] | None = None
    ) -> None:
        self._settings = settings
        self._detector = detector
        self._lock = asyncio.Lock()
        self._thread_lock = threading.Lock()

    def _load(self) -> None:
        from openmed import ModelLoader, OpenMedConfig, extract_pii

        config = OpenMedConfig(
            cache_dir=os.path.expanduser(self._settings.pulseflow_agent_pii_cache_dir),
            local_only=True,
        )
        loader = ModelLoader(config)
        model = self._settings.pulseflow_agent_pii_model
        loader.load_model(model)

        def detect(value: str) -> object:
            return extract_pii(value, lang="zh", model_name=model, loader=loader)

        self._detector = detect

    async def warm(self) -> None:
        """Fail startup before readiness if the real local PII model cannot load."""
        if self._settings.is_test_model or self._detector is not None:
            return
        async with self._lock:
            try:
                await asyncio.to_thread(self._load)
                await asyncio.to_thread(self._detect, "测试文本")
            except Exception:
                self._detector = None
                raise PiiBlockedError("pii_provider_unavailable") from None

    def _detect(self, value: str) -> bool:
        detector = self._detector
        if detector is None:
            raise RuntimeError("PII runtime unavailable")
        # Cancellation does not stop a running to_thread call. Keep model access
        # serialized even after its awaiting coroutine releases the asyncio lock.
        with self._thread_lock:
            result = detector(value)
        entities = getattr(result, "entities", None)
        if not isinstance(entities, list):
            raise ValueError("invalid PII result")
        return bool(entities)

    async def check(self, content: Any) -> None:
        with trace.get_tracer(__name__).start_as_current_span(
            "pii.preflight", record_exception=False, set_status_on_exception=False
        ) as span:
            try:
                texts = list(dict.fromkeys(_texts(content)))
                if not texts or self._settings.is_test_model:
                    span.set_attribute("pii.result", "local_pass")
                    return
                async with self._lock:
                    for value in texts:
                        try:
                            detected = await asyncio.to_thread(self._detect, value)
                        except Exception:
                            raise PiiBlockedError("pii_provider_unavailable") from None
                        if detected:
                            raise PiiBlockedError("pii_detected")
                span.set_attribute("pii.result", "pass")
            except PiiBlockedError as error:
                span.set_attribute("pii.result", error.reason)
                raise
