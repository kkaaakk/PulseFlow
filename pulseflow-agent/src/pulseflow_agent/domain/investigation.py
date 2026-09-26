"""Investigation state stays separate from visible conversation messages."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Literal
from uuid import uuid4

from opentelemetry import trace
from pydantic import BaseModel, ConfigDict, Field

from pulseflow_agent.domain.contracts import ToolMetadata, WireModel, safe_query

if TYPE_CHECKING:
    from pulseflow_agent.workspace.repository import InvestigationRepository

InvestigationStatus = Literal[
    "RUNNING", "COMPLETED", "INSUFFICIENT_EVIDENCE", "BUDGET_EXHAUSTED", "FAILED", "CANCELLED"
]
HypothesisStatus = Literal["OPEN", "SUPPORTED", "WEAKENED", "REJECTED"]


class Evidence(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    tool_name: str
    source: str
    observation: str
    query: dict[str, object]
    data_version: str | None
    warnings: list[str]
    trace_id: str | None
    collected_at: datetime
    java_query_id: str
    scope_version: int = 0


class Hypothesis(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    statement: str = Field(min_length=1, max_length=1000)
    status: HypothesisStatus
    supporting_evidence_ids: list[str] = Field(max_length=20)
    contradicting_evidence_ids: list[str] = Field(max_length=20)
    reason: str | None = Field(max_length=2000)
    confidence: Literal["low", "medium", "high"] | None
    scope_version: int
    created_at: datetime
    updated_at: datetime


class AgentMessage(BaseModel):
    role: Literal["USER", "ASSISTANT"]
    content: str = Field(max_length=4000)
    created_at: datetime
    diagnosis: Diagnosis | None = None


class Finding(BaseModel):
    claim: str = Field(min_length=1, max_length=2000)
    evidence_ids: list[str] = Field(default_factory=list)


class Diagnosis(BaseModel):
    status: Literal["DIAGNOSED", "INSUFFICIENT_EVIDENCE"]
    summary: str = Field(min_length=1, max_length=4000)
    findings: list[Finding]
    evidence_ids: list[str]
    unresolved_questions: list[str]
    confidence: Literal["low", "medium", "high"]
    recommended_next_action: str | None


class ToolObservation(BaseModel):
    evidence_id: str | None
    observation: str
    warnings: list[str] = Field(default_factory=list)


class InvestigationResult(BaseModel):
    diagnosis: Diagnosis
    evidence: list[Evidence]
    tool_trajectory: list[str]
    investigation_id: str | None = None
    hypotheses: list[Hypothesis] = Field(default_factory=list)


class Investigation(BaseModel):
    id: str
    goal: str
    status: InvestigationStatus
    scope: str | None
    scope_version: int
    created_at: datetime
    updated_at: datetime
    final_diagnosis: Diagnosis | None
    evidence: list[Evidence]
    hypotheses: list[Hypothesis]
    messages: list[AgentMessage]
    tool_trajectory: list[str]


@dataclass
class InvestigationWorkspace:
    """Mutable per-run view backed by Agent-owned storage when configured."""

    goal: str
    id: str | None = None
    status: InvestigationStatus = "RUNNING"
    scope: str | None = None
    scope_version: int = 0
    repository: InvestigationRepository | None = None
    evidence: list[Evidence] = field(default_factory=list)
    hypotheses: list[Hypothesis] = field(default_factory=list)
    messages: list[AgentMessage] = field(default_factory=list)
    tool_trajectory: list[str] = field(default_factory=list)

    async def record_tool(self, name: str) -> None:
        if self.repository is not None and self.id is not None:
            await self.repository.append_tool(self.id, name)
        self.tool_trajectory.append(name)

    async def add_evidence(
        self, name: str, metadata: ToolMetadata, observation: str, query: WireModel
    ) -> Evidence:
        context = trace.get_current_span().get_span_context()
        trace_id = f"{context.trace_id:032x}" if context.is_valid else None
        item = Evidence(
            id=str(uuid4()),
            tool_name=name,
            source=metadata.source,
            observation=observation,
            query=safe_query(query),
            data_version=metadata.data_version,
            warnings=list(metadata.warnings),
            trace_id=trace_id,
            collected_at=datetime.now(UTC),
            java_query_id=str(metadata.query_id),
            scope_version=self.scope_version,
        )
        if self.repository is not None and self.id is not None:
            await self.repository.add_evidence(self.id, item)
        self.evidence.append(item)
        return item

    async def propose_hypothesis(
        self, statement: str, supporting: list[str], contradicting: list[str],
        reason: str | None = None,
    ) -> Hypothesis:
        self._validate_evidence(supporting + contradicting)
        if set(supporting) & set(contradicting):
            raise ValueError("same evidence cannot support and contradict a hypothesis")
        now = datetime.now(UTC)
        item = Hypothesis(
            id=str(uuid4()), statement=statement, status="OPEN",
            supporting_evidence_ids=supporting,
            contradicting_evidence_ids=contradicting,
            reason=reason, confidence=None, scope_version=self.scope_version,
            created_at=now, updated_at=now,
        )
        if self.repository is not None and self.id is not None:
            await self.repository.add_hypothesis(self.id, item)
        self.hypotheses.append(item)
        return item

    async def update_hypothesis(
        self, hypothesis_id: str, status: HypothesisStatus,
        supporting: list[str], contradicting: list[str], reason: str,
        confidence: Literal["low", "medium", "high"] | None = None,
    ) -> Hypothesis:
        self._validate_evidence(supporting + contradicting)
        if set(supporting) & set(contradicting):
            raise ValueError("same evidence cannot support and contradict a hypothesis")
        if status == "SUPPORTED" and not supporting:
            raise ValueError("supported hypothesis needs supporting evidence")
        if status == "WEAKENED" and not contradicting:
            raise ValueError("weakened hypothesis needs contradicting evidence")
        for index, old in enumerate(self.hypotheses):
            if old.id != hypothesis_id or old.scope_version != self.scope_version:
                continue
            updated = Hypothesis.model_validate({**old.model_dump(),
                "status": status, "supporting_evidence_ids": supporting,
                "contradicting_evidence_ids": contradicting, "reason": reason,
                "confidence": confidence, "updated_at": datetime.now(UTC),
            })
            if self.repository is not None and self.id is not None:
                await self.repository.update_hypothesis(self.id, updated)
            self.hypotheses[index] = updated
            return updated
        raise ValueError("hypothesis_not_found_in_current_scope")

    async def change_scope(self, description: str) -> int:
        description = description.strip()
        if not description or len(description) > 1000:
            raise ValueError("scope cannot be empty")
        if description == self.scope:
            return self.scope_version
        if self.repository is not None and self.id is not None:
            await self.repository.change_scope(self.id, description, self.scope_version)
        self.scope = description
        self.scope_version += 1
        now = datetime.now(UTC)
        self.hypotheses = [
            item.model_copy(update={"status": "REJECTED", "reason": "superseded_by_scope_change",
                                    "updated_at": now})
            if item.scope_version < self.scope_version and item.status != "REJECTED" else item
            for item in self.hypotheses
        ]
        return self.scope_version

    async def add_message(
        self, role: Literal["USER", "ASSISTANT"], content: str,
        diagnosis: Diagnosis | None = None,
    ) -> None:
        item = AgentMessage(
            role=role, content=content, created_at=datetime.now(UTC), diagnosis=diagnosis
        )
        if self.repository is not None and self.id is not None:
            await self.repository.add_message(self.id, item)
        self.messages.append(item)

    async def finish(self, status: InvestigationStatus, diagnosis: Diagnosis) -> None:
        if self.repository is not None and self.id is not None:
            await self.repository.finish(self.id, status, diagnosis)
        self.status = status
        self.messages.append(AgentMessage(
            role="ASSISTANT", content=diagnosis.summary,
            created_at=datetime.now(UTC), diagnosis=diagnosis,
        ))

    def context_text(self) -> str:
        prior = (
            self.messages[:-1]
            if self.messages and self.messages[-1].role == "USER" else self.messages
        )
        recent = [f"{item.role}: {item.content}" for item in prior[-4:]]
        facts = [
            f"{item.id} "
            f"[{'CURRENT' if item.scope_version == self.scope_version else 'BACKGROUND'}]: "
            f"{item.observation}"
            for item in self.evidence[-12:]
        ]
        hypotheses = [
            f"{item.id} [{item.status}]: {item.statement}"
            for item in self.hypotheses[-8:]
        ]
        return (f"Investigation goal: {self.goal}\nCurrent scope: {self.scope or 'overall'}\n"
                + "Prior visible conversation:\n" + "\n".join(recent)
                + "\nEvidence (BACKGROUND is historical, not proof for current scope):\n"
                + "\n".join(facts) + "\nHypotheses:\n" + "\n".join(hypotheses))[:6000]

    def _validate_evidence(self, ids: list[str]) -> None:
        if not set(ids).issubset(self.evidence_ids):
            raise ValueError("hypothesis references unknown or historical evidence")

    @property
    def evidence_ids(self) -> set[str]:
        return {item.id for item in self.evidence if item.scope_version == self.scope_version}
