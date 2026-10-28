"""Investigation state stays separate from visible conversation messages."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Literal
from uuid import uuid4

from opentelemetry import trace
from pydantic import BaseModel, ConfigDict, Field

from pulseflow_agent.domain.campaign_proposal import (
    CampaignProposal,
    ProposalRecord,
)
from pulseflow_agent.domain.contracts import ToolMetadata, WireModel, safe_query

if TYPE_CHECKING:
    from pulseflow_agent.workspace.repository import InvestigationRepository

InvestigationStatus = Literal[
    "RUNNING", "COMPLETED", "INSUFFICIENT_EVIDENCE", "BUDGET_EXHAUSTED", "FAILED", "CANCELLED"
]
HypothesisStatus = Literal["OPEN", "SUPPORTED", "WEAKENED", "REJECTED"]


# 来自 Java 聚合工具的可追溯证据，绑定查询、数据及范围版本。
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


# 带支持和反对证据的调查假设，记录状态、理由和置信度。
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


# 可展示的用户或助手消息，可附结构化诊断。
class AgentMessage(BaseModel):
    role: Literal["USER", "ASSISTANT"]
    content: str = Field(max_length=4000)
    created_at: datetime
    diagnosis: Diagnosis | None = None


# 诊断中的单项声明及其支持证据 ID。
class Finding(BaseModel):
    claim: str = Field(min_length=1, max_length=2000)
    evidence_ids: list[str] = Field(default_factory=list)


# 最终诊断，包含发现、证据引用、未解决问题和置信度。
class Diagnosis(BaseModel):
    status: Literal["DIAGNOSED", "INSUFFICIENT_EVIDENCE"]
    summary: str = Field(min_length=1, max_length=4000)
    findings: list[Finding]
    evidence_ids: list[str]
    unresolved_questions: list[str]
    confidence: Literal["low", "medium", "high"]
    recommended_next_action: str | None


# 工具交给模型的紧凑观察，附可引用证据 ID 与口径警告。
class ToolObservation(BaseModel):
    evidence_id: str | None
    observation: str
    warnings: list[str] = Field(default_factory=list)


# 单轮运行结果，包含诊断及本轮工作区中积累的业务对象。
class InvestigationResult(BaseModel):
    diagnosis: Diagnosis
    evidence: list[Evidence]
    tool_trajectory: list[str]
    investigation_id: str | None = None
    hypotheses: list[Hypothesis] = Field(default_factory=list)
    proposals: list[ProposalRecord] = Field(default_factory=list)


# 可从数据库恢复和向接口返回的完整调查快照。
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
    proposals: list[ProposalRecord] = Field(default_factory=list)


# 单轮可变调查视图，先持久化再更新内存，并约束当前范围证据引用。
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
    proposals: list[ProposalRecord] = field(default_factory=list)

    # 验证当前范围证据后持久化方案及操作员归属，并将记录加入工作区。
    async def add_proposal(self, proposal: CampaignProposal, owner_id: int) -> ProposalRecord:
        if self.id is None or self.repository is None:
            raise ValueError("proposal requires a persisted investigation")
        self._validate_evidence(proposal.supporting_evidence_ids)
        item = ProposalRecord(
            id=str(uuid4()),
            investigation_id=self.id,
            owner_id=owner_id,
            proposal=proposal,
            status="GENERATED",
            scope_version=self.scope_version,
            created_at=datetime.now(UTC),
            updated_at=datetime.now(UTC),
        )
        await self.repository.add_proposal(self.id, item)
        self.proposals.append(item)
        return item

    # 先保存工具轨迹再更新内存列表，使恢复后的调查仍能展示执行过程。
    async def record_tool(self, name: str) -> None:
        if self.repository is not None and self.id is not None:
            await self.repository.append_tool(self.id, name)
        self.tool_trajectory.append(name)

    # 创建证据 UUID，记录来源、版本、trace 和范围版本；持久化后返回证据。
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

    # 核验证据且支持与反对集合不重叠，创建 OPEN 假设并同步到存储。
    async def propose_hypothesis(
        self,
        statement: str,
        supporting: list[str],
        contradicting: list[str],
        reason: str | None = None,
    ) -> Hypothesis:
        self._validate_evidence(supporting + contradicting)
        if set(supporting) & set(contradicting):
            raise ValueError("same evidence cannot support and contradict a hypothesis")
        now = datetime.now(UTC)
        item = Hypothesis(
            id=str(uuid4()),
            statement=statement,
            status="OPEN",
            supporting_evidence_ids=supporting,
            contradicting_evidence_ids=contradicting,
            reason=reason,
            confidence=None,
            scope_version=self.scope_version,
            created_at=now,
            updated_at=now,
        )
        if self.repository is not None and self.id is not None:
            await self.repository.add_hypothesis(self.id, item)
        self.hypotheses.append(item)
        return item

    # 更新当前范围假设；支持或削弱状态须有对应证据，版本不符则拒绝。
    async def update_hypothesis(
        self,
        hypothesis_id: str,
        status: HypothesisStatus,
        supporting: list[str],
        contradicting: list[str],
        reason: str,
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
            updated = Hypothesis.model_validate(
                {
                    **old.model_dump(),
                    "status": status,
                    "supporting_evidence_ids": supporting,
                    "contradicting_evidence_ids": contradicting,
                    "reason": reason,
                    "confidence": confidence,
                    "updated_at": datetime.now(UTC),
                }
            )
            if self.repository is not None and self.id is not None:
                await self.repository.update_hypothesis(self.id, updated)
            self.hypotheses[index] = updated
            return updated
        raise ValueError("hypothesis_not_found_in_current_scope")

    # 应用新范围并递增版本、拒绝旧假设；相同范围直接返回原版本。
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
            item.model_copy(
                update={
                    "status": "REJECTED",
                    "reason": "superseded_by_scope_change",
                    "updated_at": now,
                }
            )
            if item.scope_version < self.scope_version and item.status != "REJECTED"
            else item
            for item in self.hypotheses
        ]
        return self.scope_version

    # 记录带时间戳的会话消息及可选诊断，供后续追问恢复上下文。
    async def add_message(
        self,
        role: Literal["USER", "ASSISTANT"],
        content: str,
        diagnosis: Diagnosis | None = None,
    ) -> None:
        item = AgentMessage(
            role=role, content=content, created_at=datetime.now(UTC), diagnosis=diagnosis
        )
        if self.repository is not None and self.id is not None:
            await self.repository.add_message(self.id, item)
        self.messages.append(item)

    # 持久化调查终态和诊断，并追加包含摘要的助手消息。
    async def finish(self, status: InvestigationStatus, diagnosis: Diagnosis) -> None:
        if self.repository is not None and self.id is not None:
            await self.repository.finish(self.id, status, diagnosis)
        self.status = status
        self.messages.append(
            AgentMessage(
                role="ASSISTANT",
                content=diagnosis.summary,
                created_at=datetime.now(UTC),
                diagnosis=diagnosis,
            )
        )

    # 只接受当前范围已有证据 ID，未知或历史引用抛出校验错误。
    def _validate_evidence(self, ids: list[str]) -> None:
        if not set(ids).issubset(self.evidence_ids):
            raise ValueError("hypothesis references unknown or historical evidence")

    # 返回当前范围证据 ID 集合，统一约束诊断、假设及方案引用。
    @property
    def evidence_ids(self) -> set[str]:
        return {item.id for item in self.evidence if item.scope_version == self.scope_version}
