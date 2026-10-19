"""Select complete, traceable business context from a run-entry workspace snapshot.

No storage, tool, model calls or state transitions belong here. Text matching only
ranks candidates; an unknown selector never makes an evidence record ineligible.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import date
from typing import TYPE_CHECKING, Any

from pydantic import ValidationError
from pydantic_ai.exceptions import UsageLimitExceeded

from pulseflow_agent.domain.contracts import (
    AttributionArgs,
    BreakdownMetricArgs,
    CampaignPerformanceArgs,
    CompareMetricArgs,
    FilterField,
    QueryMetricArgs,
    TimeRange,
)
from pulseflow_agent.domain.investigation import Evidence, Hypothesis, InvestigationWorkspace

if TYPE_CHECKING:
    from pulseflow_agent.config import AgentSettings

_METRICS = {
    "SENT": ("sent", "发送量", "发送数"),
    "DELIVERED": ("delivered", "送达量", "送达数"),
    "CLICKS": ("clicks", "点击量", "点击数"),
    "CONVERSIONS": ("conversions", "转化量", "转化数"),
    "CTR": ("ctr", "click-through rate", "点击率"),
    "CONVERSION_RATE": ("conversion_rate", "conversion rate", "转化率"),
    "ATTRIBUTED_CONVERSIONS": ("attributed_conversions", "归因转化"),
}
_CHANNELS = {"PUSH": ("push", "推送"), "IN_APP": ("in_app", "站内信"), "EMAIL": ("email", "邮件")}
_CAMPAIGN = re.compile(r"(?:\bcampaign(?:[\s_]*id)?\b|活动(?:\s*ID)?)\s*[:=#：]?\s*([0-9]+)", re.I)
_DATE = re.compile(r"(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])")
_QUERY_TYPES: dict[
    str,
    type[
        QueryMetricArgs
        | CompareMetricArgs
        | BreakdownMetricArgs
        | CampaignPerformanceArgs
        | AttributionArgs
    ],
] = {
    "query_metric": QueryMetricArgs,
    "compare_metric": CompareMetricArgs,
    "breakdown_metric": BreakdownMetricArgs,
    "get_campaign_performance": CampaignPerformanceArgs,
    "get_attribution_breakdown": AttributionArgs,
}
_POLICY = (
    "Run-entry history; current-run tool messages supersede it. Evidence contains observed "
    "values, not causal proof. Business hints only rank relevance. Hypotheses (including "
    "SUPPORTED) and prior_diagnosis are assessments, not confirmed facts. BACKGROUND and "
    "conversation cannot prove current-scope findings. Cite only current evidence IDs."
)


class ContextBudgetExceeded(UsageLimitExceeded):
    """Protected whole units do not fit; never expose their contents in an error."""

    def __init__(self, required: int, budget: int) -> None:
        self.required = required
        self.budget = budget
        super().__init__(f"Protected context exceeds text budget ({required} > {budget}).")


def estimate_tokens(text: str) -> int:
    """Conservative text estimate: one token per UTF-8 byte, NOT provider usage.

    For byte-based tokenizers the text count is between 1 and B for B nonempty
    bytes, so this can overestimate by B-1. Provider framing/tool schemas are
    outside that envelope and need a separate reserve plus SDK UsageLimits.
    """
    return len(text.encode("utf-8"))


def context_token_budget(
    settings: AgentSettings,
    *,
    input_tokens_used: int,
    output_tokens_used: int,
    fixed_text: str,
) -> int:
    """Leave input room for schemas, SDK history and subsequent tool/model turns.

    Cumulative usage is also a deliberately conservative bound on prior history
    for the operational model window. The reserve is planning headroom, not a
    promise that all max_model_requests will fit or a provider preflight count.
    """
    overhead = estimate_tokens(fixed_text) + settings.pulseflow_agent_context_reserve_tokens
    return max(
        0,
        min(
            settings.pulseflow_agent_context_tokens,
            settings.pulseflow_agent_max_input_tokens - input_tokens_used - overhead,
            settings.pulseflow_agent_context_window_tokens
            - input_tokens_used
            - output_tokens_used
            - overhead
            - 2000,
        ),
    )


@dataclass(frozen=True)
class _BusinessTerms:
    metrics: frozenset[str] = frozenset()
    channels: frozenset[str] = frozenset()
    campaign_ids: frozenset[str] = frozenset()
    dates: frozenset[date] = frozenset()
    time_ranges: tuple[TimeRange, ...] = ()


def _mentions(text: str, aliases: tuple[str, ...]) -> bool:
    return any(
        (alias in text)
        if not alias.isascii()
        else re.search(r"(?<![a-z0-9_])" + re.escape(alias) + r"(?![a-z0-9_])", text) is not None
        for alias in aliases
    )


def _text_terms(text: str) -> _BusinessTerms:
    text = text.casefold()
    dates = set()
    for match in _DATE.findall(text):
        try:
            dates.add(date.fromisoformat(match))
        except ValueError:
            pass  # Unknown dates/relative periods do not filter out evidence.
    return _BusinessTerms(
        metrics=frozenset(key for key, aliases in _METRICS.items() if _mentions(text, aliases)),
        channels=frozenset(key for key, aliases in _CHANNELS.items() if _mentions(text, aliases)),
        campaign_ids=frozenset(
            value.lstrip("0") for value in _CAMPAIGN.findall(text) if value.lstrip("0")
        ),
        dates=frozenset(dates),
    )


def _query_terms(item: Evidence) -> _BusinessTerms:
    """Read the existing validated selectors, never reverse-parse Observation."""
    query_type = _QUERY_TYPES.get(item.tool_name)
    if query_type is None:
        # safe_query intentionally saves only these selectors for audience preview.
        channel = item.query.get("channel") if item.tool_name == "preview_audience" else None
        return _BusinessTerms(
            channels=frozenset([channel])
            if isinstance(channel, str) and channel in _CHANNELS
            else frozenset()
        )
    try:
        args = query_type.model_validate(item.query)
    except ValidationError:
        return _BusinessTerms()  # Legacy/unrecognised query stays eligible, verbatim.
    metrics: set[str] = set()
    channels: set[str] = set()
    campaigns: set[str] = set()
    ranges: tuple[TimeRange, ...] = ()
    if isinstance(args, CampaignPerformanceArgs):
        campaigns.add(str(args.campaign_id))
    else:
        metrics.add(
            "ATTRIBUTED_CONVERSIONS" if isinstance(args, AttributionArgs) else args.metric.value
        )
        ranges = (
            (args.current_period, args.baseline_period)
            if isinstance(args, CompareMetricArgs)
            else (args.time_range,)
        )
        for selector in args.filters:
            if selector.field == FilterField.CHANNEL:
                channels.update(selector.values)
            elif selector.field == FilterField.CAMPAIGN_ID:
                campaigns.update(value.lstrip("0") for value in selector.values)
    return _BusinessTerms(
        frozenset(metrics), frozenset(channels), frozenset(campaigns), time_ranges=ranges
    )


def _match(focus: _BusinessTerms, query: _BusinessTerms) -> int:
    # Question metric outranks other selectors. Dates are broad overlap hints,
    # not invented timezone-aware Tool conditions (the original query is retained).
    time_match = bool(focus.dates) and any(
        period.from_inclusive.date() <= max(focus.dates)
        and period.to_exclusive.date() >= min(focus.dates)
        for period in query.time_ranges
    )
    return (
        16 * bool(focus.metrics & query.metrics)
        + 8 * bool(focus.campaign_ids & query.campaign_ids)
        + 4 * bool(focus.channels & query.channels)
        + 2 * time_match
    )


def _render(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


class ContextBuilder:
    """A small immutable-input selector, captured BEFORE the current USER message.

    Only scope classification and the available budget change during a run.
    New Evidence/Hypothesis/Message records are already carried by the SDK loop.
    """

    def __init__(self, workspace: InvestigationWorkspace, question: str) -> None:
        self.question = question
        self._goal = workspace.goal
        self._id = workspace.id
        self._evidence = tuple(item.model_copy(deep=True) for item in workspace.evidence)
        self._hypotheses = tuple(item.model_copy(deep=True) for item in workspace.hypotheses)
        self._messages = tuple(item.model_copy(deep=True) for item in workspace.messages)

    def build(
        self,
        *,
        scope: str | None,
        scope_version: int,
        token_budget: int,
        operator_authorized_promotion_facts: list[dict[str, Any]] | None = None,
    ) -> str:
        question, scoped, goal = map(_text_terms, (self.question, scope or "", self._goal))
        evidence = {item.id: item for item in self._evidence}
        current_ids = {item.id for item in self._evidence if item.scope_version == scope_version}

        def score(item: Evidence) -> int:
            query = _query_terms(item)
            return (
                10000 * _match(question, query) + 100 * _match(scoped, query) + _match(goal, query)
            )

        def evidence_payload(item: Evidence) -> dict[str, Any]:
            return {
                **item.model_dump(mode="json", exclude={"trace_id"}),
                "scope": "CURRENT" if item.scope_version == scope_version else "BACKGROUND",
            }

        def references(item: Hypothesis) -> set[str]:
            return set(item.supporting_evidence_ids + item.contradicting_evidence_ids)

        def valid_hypothesis(item: Hypothesis) -> bool:
            return (
                references(item).issubset(current_ids)
                and not set(item.supporting_evidence_ids) & set(item.contradicting_evidence_ids)
                and (item.status != "SUPPORTED" or bool(item.supporting_evidence_ids))
                and (item.status != "WEAKENED" or bool(item.contradicting_evidence_ids))
            )

        def hypothesis_payload(item: Hypothesis) -> dict[str, Any]:
            return {
                **item.model_dump(mode="json", exclude={"created_at"}),
                "scope": "CURRENT"
                if item.scope_version == scope_version and valid_hypothesis(item)
                else "BACKGROUND",
            }

        active = [
            item
            for item in self._hypotheses
            if item.scope_version == scope_version and valid_hypothesis(item)
        ]
        protected = {ref for item in active for ref in references(item)}
        latest = next((item.diagnosis for item in reversed(self._messages) if item.diagnosis), None)
        diagnosis_refs = set(latest.evidence_ids) if latest else set()
        if latest:
            diagnosis_refs.update(
                ref for finding in latest.findings for ref in finding.evidence_ids
            )
        valid_diagnosis = bool(
            latest
            and diagnosis_refs
            and diagnosis_refs.issubset(current_ids)
            and all(set(f.evidence_ids).issubset(latest.evidence_ids) for f in latest.findings)
        )
        if valid_diagnosis:
            protected.update(diagnosis_refs)

        payload: dict[str, Any] = {
            "context_policy": _POLICY,
            "investigation": {
                "id": self._id,
                "goal": self._goal,
                "scope": scope,
                "scope_version": scope_version,
            },
            "business_context": {
                "metrics": sorted(question.metrics or scoped.metrics or goal.metrics),
                "channels": sorted(question.channels or scoped.channels or goal.channels),
                "campaign_ids": sorted(
                    question.campaign_ids or scoped.campaign_ids or goal.campaign_ids
                ),
                "date_hints": sorted(
                    d.isoformat() for d in (question.dates or scoped.dates or goal.dates)
                ),
            },
            "relevant_evidence": [
                evidence_payload(evidence[ref])
                for ref in sorted(protected, key=lambda ref: (-score(evidence[ref]), ref))
            ],
            "hypotheses": [hypothesis_payload(item) for item in active],
            "prior_diagnosis": (
                {"scope": "CURRENT", **latest.model_dump(mode="json")}
                if valid_diagnosis and latest
                else None
            ),
            "recent_conversation": [],
            "unresolved_questions": [],
        }
        if operator_authorized_promotion_facts is not None:
            payload["operator_authorized_promotion_facts"] = operator_authorized_promotion_facts
            payload["promotion_facts_policy"] = (
                "Operator-authorized promotion facts (do not add or replace)"
            )
        # Only the latest assessment is reused; no older conclusion becomes a new fact.
        if latest and valid_diagnosis:
            payload["unresolved_questions"] = [
                {"question": value, "scope": "CURRENT" if valid_diagnosis else "BACKGROUND"}
                for value in dict.fromkeys(latest.unresolved_questions)
            ]
        if payload["prior_diagnosis"]:
            payload["prior_diagnosis"].pop("unresolved_questions")

        def render(candidate: dict[str, Any]) -> str:
            candidate["selection"] = {
                "omitted_evidence": len(evidence) - len(candidate["relevant_evidence"]),
                "omitted_hypotheses": len(self._hypotheses) - len(candidate["hypotheses"]),
                "omitted_messages": len(self._messages) - len(candidate["recent_conversation"]),
                "text_budget": token_budget,
                "token_estimator": "utf8_bytes_upper_estimate",
            }
            return _render(candidate)

        required = estimate_tokens(render(payload))
        if required > token_budget:
            raise ContextBudgetExceeded(required, token_budget)

        # Optional units have dependencies: include an old hypothesis/diagnosis
        # only together with every referenced evidence record, never dangling IDs.
        candidates: list[tuple[int, int, int, str, Any]] = []
        for index, item in enumerate(self._evidence):
            if item.id not in protected:
                relevance = score(item)
                tier = (1 if relevance else 2) if item.id in current_ids else 3
                candidates.append((tier, relevance, index, "evidence", item))
        for index, hypothesis in enumerate(self._hypotheses):
            if hypothesis not in active and references(hypothesis).issubset(evidence):
                candidates.append((3, 0, index, "hypothesis", hypothesis))
        if latest and not valid_diagnosis and diagnosis_refs.issubset(evidence):
            candidates.append((3, 0, len(self._messages), "diagnosis", latest))
        if latest and not valid_diagnosis and latest.unresolved_questions:
            candidates.append(
                (
                    2,
                    0,
                    len(self._evidence) + len(self._messages),
                    "questions",
                    latest.unresolved_questions,
                )
            )
        seen_messages: set[tuple[str, str]] = set()
        for index in reversed(range(len(self._messages))):
            message = self._messages[index]
            key = (message.role, message.content)
            if (
                message.diagnosis
                or message.content in {self._goal, self.question}
                or key in seen_messages
            ):
                continue
            seen_messages.add(key)
            candidates.append(
                (
                    2 if index >= len(self._messages) - 2 else 3,
                    0,
                    len(self._evidence) + index,
                    "message",
                    message,
                )
            )

        for _, _, _, kind, unit in sorted(candidates, key=lambda row: (row[0], -row[1], -row[2])):
            candidate = {
                **payload,
                "relevant_evidence": list(payload["relevant_evidence"]),
                "hypotheses": list(payload["hypotheses"]),
                "recent_conversation": list(payload["recent_conversation"]),
            }
            refs: set[str] = set()
            if kind == "evidence":
                refs.add(unit.id)
            elif kind == "hypothesis":
                candidate["hypotheses"].append(hypothesis_payload(unit))
                refs.update(references(unit))
            elif kind == "diagnosis":
                candidate["prior_diagnosis"] = {
                    "scope": "BACKGROUND",
                    **unit.model_dump(mode="json", exclude={"unresolved_questions"}),
                }
                refs.update(diagnosis_refs)
            elif kind == "questions":
                candidate["unresolved_questions"] = [
                    {"question": value, "scope": "BACKGROUND"} for value in dict.fromkeys(unit)
                ]
            else:
                candidate["recent_conversation"].insert(
                    0,
                    {
                        "role": unit.role,
                        "content": unit.content,
                        "created_at": unit.created_at.isoformat(),
                        "scope": "UNVERIFIED_HISTORY",
                    },
                )
            present = {entry["id"] for entry in candidate["relevant_evidence"]}
            candidate["relevant_evidence"].extend(
                evidence_payload(evidence[ref]) for ref in sorted(refs - present)
            )
            if estimate_tokens(render(candidate)) <= token_budget:
                payload = candidate
        return render(payload)
