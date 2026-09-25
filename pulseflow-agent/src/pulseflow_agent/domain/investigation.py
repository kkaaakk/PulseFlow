"""Ephemeral Phase 3 investigation state and evidence-backed output."""

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Literal
from uuid import uuid4

from opentelemetry import trace
from pydantic import BaseModel, ConfigDict, Field

from pulseflow_agent.domain.contracts import ToolMetadata, WireModel, safe_query


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


class Finding(BaseModel):
    claim: str
    evidence_ids: list[str] = Field(default_factory=list)


class Diagnosis(BaseModel):
    status: Literal["DIAGNOSED", "INSUFFICIENT_EVIDENCE"]
    summary: str
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


@dataclass
class InvestigationWorkspace:
    """Isolated in memory for one run; Phase 4 will add persistence."""

    goal: str
    evidence: list[Evidence] = field(default_factory=list)
    tool_trajectory: list[str] = field(default_factory=list)

    def record_tool(self, name: str) -> None:
        self.tool_trajectory.append(name)

    def add_evidence(
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
        )
        self.evidence.append(item)
        return item

    @property
    def evidence_ids(self) -> set[str]:
        return {item.id for item in self.evidence}
