"""Fail-closed checks before any content is sent to a model."""

import math
import re
from collections.abc import Mapping
from typing import Any

import httpx
from opentelemetry import trace
from opentelemetry.instrumentation.utils import suppress_instrumentation
from pydantic import BaseModel

from pulseflow_agent.config import AgentSettings
from pulseflow_agent.security.pii_entities import AZURE_TYPES, PiiEntity, blocking_entities
from pulseflow_agent.security.structured_pii import StructuredPiiDetector

_BLOCKED_FIELDS = frozenset(
    {
        "userid",
        "userids",
        "mobile",
        "phone",
        "email",
        "address",
        "idcard",
        "idnumber",
        "deviceid",
        "imei",
        "rawevents",
        "orderdetails",
        "behaviourlogs",
        "fullname",
        "realname",
    }
)
_FIELD_MENTION = re.compile(
    r"(?<![A-Za-z0-9_])(?:"
    + "|".join(sorted(_BLOCKED_FIELDS, key=len, reverse=True))
    + r")(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
_FIELD_NAME = r"(?:" + "|".join(sorted(_BLOCKED_FIELDS, key=len, reverse=True)) + r")"
_QUOTED_FIELD = rf'(?:{_FIELD_NAME}|`{_FIELD_NAME}`|"{_FIELD_NAME}")'
_ATTRIBUTE = (
    r"(?:含义|定义|数据类型|命名规范|文档用途|保留期限定义|缺失率的计算公式|数据质量指标定义)"
)
_CONCEPT_QUESTION = re.compile(
    rf"(?:"
    rf"(?:请)?(?:解释|说明|定义)\s*{_QUOTED_FIELD}\s*字段(?:的)?{_ATTRIBUTE}"
    rf"|{_QUOTED_FIELD}\s*字段(?:在数据字典中)?(?:的)?{_ATTRIBUTE}(?:是什么|如何定义)?"
    rf"|{_QUOTED_FIELD}\s*字段是什么意思"
    rf"|What does (?:the field )?{_QUOTED_FIELD} mean"
    rf"|What is the (?:definition|data type|retention policy) of (?:field )?{_QUOTED_FIELD}"
    rf"|(?:Define|Explain) (?:the )?(?:meaning|data type|naming convention) of "
    rf"(?:field )?{_QUOTED_FIELD}"
    rf")[。？?！!.]?",
    re.IGNORECASE,
)


def is_field_concept_question(text: str) -> bool:
    """A complete metadata-only grammar, never an instruction/keyword exemption.

    No arbitrary values, additional clauses, JSON or export operations fit this
    grammar. Structured forbidden keys still fail before any child is inspected.
    Azure and local validation still run on allowed questions.
    """
    return _CONCEPT_QUESTION.fullmatch(text.strip()) is not None


class PiiBlockedError(Exception):
    """A deliberately content-free error safe for API and logs."""

    # 仅保存不含原文的阻断原因码，供 HTTP 响应和遥测安全使用。
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


# 递归提取模型、映射和列表中的非空文本，同时检查 Python 字段名、序列化别名及受禁业务字段；
# 发现违规立即抛出。
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
        if _FIELD_MENTION.search(value) and not is_field_concept_question(value):
            raise PiiBlockedError("blocked_business_field")
        return [value] if value.strip() else []
    return []


class AzurePiiGuardrail:
    # 保存配置与共享 HTTP 客户端，创建本地结构化 PII 检测器。
    def __init__(self, settings: AgentSettings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client
        self._structured = StructuredPiiDetector()

    # 先检查字段并去重文本；离线模型仅做该本地检查，真实模型继续 Azure 与结构化验证，
    # 遥测仅记录结果码。
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

    # 逐条合并 Azure 语义实体与本地结构校验结果；检测器故障或发现可阻断实体均抛出内容无关的安全
    # 异常。
    async def _check_azure(self, texts: list[str]) -> None:
        for value in texts:
            # Always inspect Azure as well, keeping its existing fail-closed contract.
            azure = await self._detect_azure(value)
            try:
                local = self._structured.detect(value)
                blockable = blocking_entities(azure, local)
            except Exception:
                raise PiiBlockedError("pii_validator_unavailable") from None
            if blockable:
                raise PiiBlockedError("pii_detected")

    # 调用开启 loggingOptOut 的 Azure PII 接口，抑制该请求遥测并严格校验响应结构；
    # 配置、网络或契约异常一律拒绝继续。
    async def _detect_azure(self, value: str) -> list[PiiEntity]:
        endpoint = self._settings.azure_language_endpoint
        key = self._settings.azure_language_key
        if endpoint is None or key is None:
            raise PiiBlockedError("pii_provider_unavailable")
        try:
            # The shared client is instrumented. Do not export the Azure resource
            # hostname, request headers, or exception events from this privacy check.
            with suppress_instrumentation():
                response = await self._client.post(
                    f"{str(endpoint).rstrip('/')}/language/:analyze-text",
                    params={"api-version": "2024-11-01"},
                    headers={"Ocp-Apim-Subscription-Key": key.get_secret_value()},
                    json={
                        "kind": "PiiEntityRecognition",
                        "parameters": {
                            "modelVersion": "latest",
                            "loggingOptOut": True,
                            "stringIndexType": "UnicodeCodePoint",
                        },
                        "analysisInput": {
                            "documents": [
                                {
                                    "id": "1",
                                    "language": self._settings.azure_language_pii_language,
                                    "text": value,
                                }
                            ]
                        },
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
            return _parse_entities(entities, value)
        except PiiBlockedError:
            raise
        except Exception:
            raise PiiBlockedError("pii_provider_unavailable") from None


# 校验 Azure 实体标签、码点边界、长度和有限置信分数，转换为不含匹配原文的内部 PiiEntity。
def _parse_entities(entities: list[Any], text: str) -> list[PiiEntity]:
    parsed = []
    for item in entities:
        if not isinstance(item, dict):
            raise ValueError("invalid entity")
        label, start, length = item.get("category"), item.get("offset"), item.get("length")
        score, subcategory = item.get("confidenceScore"), item.get("subcategory")
        if (
            not isinstance(label, str)
            or not label
            or type(start) is not int
            or type(length) is not int
            or not 0 <= start < start + length <= len(text)
            or not isinstance(score, (int, float))
            or isinstance(score, bool)
            or not math.isfinite(score)
            or not 0 <= score <= 1
            or (subcategory is not None and not isinstance(subcategory, str))
        ):
            raise ValueError("invalid entity")
        parsed.append(
            PiiEntity(
                start,
                start + length,
                label,
                AZURE_TYPES.get(label, "OTHER_PII"),
                float(score),
                ("azure",),
                subcategory,
            )
        )
    return parsed
