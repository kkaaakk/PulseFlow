"""Fail-closed checks before any content is sent to a model."""

import re
from collections.abc import Mapping
from typing import Any

import httpx
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


class AzurePiiGuardrail:
    def __init__(self, settings: AgentSettings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client

    async def check(self, content: Any) -> None:
        with trace.get_tracer(__name__).start_as_current_span(
            "pii.preflight", record_exception=False, set_status_on_exception=False
        ) as span:
            try:
                texts = list(dict.fromkeys(_texts(content)))  # local check before Azure
                if not texts or self._settings.is_test_model:
                    span.set_attribute("pii.result", "local_pass")
                    return
                await self._check_azure(texts)
                span.set_attribute("pii.result", "pass")
            except PiiBlockedError as error:
                span.set_attribute("pii.result", error.reason)
                raise

    async def _check_azure(self, texts: list[str]) -> None:
        endpoint = self._settings.azure_language_endpoint
        key = self._settings.azure_language_key
        if endpoint is None or key is None:
            raise PiiBlockedError("pii_provider_unavailable")
        for value in texts:
            try:
                response = await self._client.post(
                    f"{str(endpoint).rstrip('/')}/language/:analyze-text",
                    params={"api-version": "2024-11-01"},
                    headers={"Ocp-Apim-Subscription-Key": key.get_secret_value()},
                    json={
                        "kind": "PiiEntityRecognition",
                        "parameters": {"modelVersion": "latest"},
                        "analysisInput": {"documents": [
                            {"id": "1", "language": self._settings.azure_language_pii_language,
                             "text": value}
                        ]},
                    },
                    timeout=5.0,
                )
                if response.status_code != 200:
                    raise PiiBlockedError("pii_provider_unavailable")
                data = response.json()
                if not isinstance(data, dict) or data.get("kind") != "PiiEntityRecognitionResults":
                    raise PiiBlockedError("pii_provider_unavailable")
                results = data.get("results")
                if not isinstance(results, dict) or results.get("errors") != []:
                    raise PiiBlockedError("pii_provider_unavailable")
                documents = results.get("documents")
                if not isinstance(documents, list) or len(documents) != 1:
                    raise PiiBlockedError("pii_provider_unavailable")
                document = documents[0]
                if not isinstance(document, dict) or document.get("id") != "1":
                    raise PiiBlockedError("pii_provider_unavailable")
                entities = document.get("entities")
                if not isinstance(entities, list):
                    raise PiiBlockedError("pii_provider_unavailable")
                if entities:
                    raise PiiBlockedError("pii_detected")
            except PiiBlockedError:
                raise
            except Exception:
                raise PiiBlockedError("pii_provider_unavailable") from None
