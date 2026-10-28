"""Repository contract and SQLAlchemy implementation for Agent-owned state."""

import asyncio
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Protocol
from uuid import uuid4

from sqlalchemy import insert, select, update
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine

from pulseflow_agent.domain.campaign_proposal import (
    CampaignProposal,
    ProposalRead,
    ProposalRecord,
)
from pulseflow_agent.domain.investigation import (
    AgentMessage,
    Diagnosis,
    Evidence,
    Hypothesis,
    Investigation,
    InvestigationStatus,
)
from pulseflow_agent.workspace.models import evidence, hypothesis, investigation, message, metadata
from pulseflow_agent.workspace.models import proposal as proposal_table


# 调查、假设或方案记录不存在时使用的存储异常。
class InvestigationNotFoundError(Exception):
    pass


# 调查正运行、范围版本过期或方案关联冲突时使用的状态异常。
class InvestigationConflictError(Exception):
    pass


# 调查存储协议，约束证据、假设、消息、范围与方案的持久化操作。
class InvestigationRepository(Protocol):
    # 保存方案、操作员归属和范围版本，保留方案生成与 Java 草稿创建的边界。
    async def add_proposal(self, investigation_id: str, item: ProposalRecord) -> None: ...
    # 读取方案及其范围版本对应的证据 ID，供 Java 再次核验引用。
    async def load_proposal(self, proposal_id: str) -> ProposalRead: ...
    # 锁定方案后幂等关联 Java 草稿 ID；已关联其他草稿时拒绝冲突。
    async def mark_draft_created(self, proposal_id: str, draft_id: int) -> ProposalRecord: ...
    # 按目标生成 UUID 和 UTC 时间，插入 RUNNING 记录并返回调查对象。
    async def create(self, goal: str) -> Investigation: ...
    # 按 ID 重建证据、假设、消息及方案；记录不存在时抛出未找到异常。
    async def load(self, investigation_id: str) -> Investigation: ...
    # 条件更新允许恢复的终态为 RUNNING，区分记录不存在与并发冲突。
    async def begin_followup(self, investigation_id: str) -> Investigation: ...
    # 锁定调查行后追加工具名，避免并发调用覆盖轨迹。
    async def append_tool(self, investigation_id: str, name: str) -> None: ...
    # 插入带查询元数据和范围版本的证据，并刷新调查更新时间。
    async def add_evidence(self, investigation_id: str, item: Evidence) -> None: ...
    # 保存假设、支持和反对证据、置信度及版本，供调查恢复使用。
    async def add_hypothesis(self, investigation_id: str, item: Hypothesis) -> None: ...
    # 按调查归属和版本更新假设，拒绝不存在或版本不匹配的修改。
    async def update_hypothesis(self, investigation_id: str, item: Hypothesis) -> None: ...
    # 以旧版本作乐观锁更新范围，在同一事务中将历史假设标记为 REJECTED。
    async def change_scope(self, investigation_id: str, scope: str, old_version: int) -> None: ...
    # 保存会话消息和可选诊断，并刷新调查更新时间。
    async def add_message(self, investigation_id: str, item: AgentMessage) -> None: ...
    # 同事务更新 RUNNING 调查为终态，写入诊断与助手消息。
    async def finish(
        self, investigation_id: str, status: InvestigationStatus, diagnosis: Diagnosis
    ) -> None: ...


# 无时区时间按 UTC 解释，有时区时间转为 UTC，统一数据库驱动时间表示。
def _utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


# 基于异步 SQLAlchemy 的 Agent 自有数据库仓储。
class SqlAlchemyInvestigationRepository:
    # 保存异步引擎；内存 SQLite 串行访问连接，避免读连接回滚并发写入。
    def __init__(self, engine: AsyncEngine) -> None:
        self._engine = engine
        # In-memory SQLite shares one connection; concurrent readers must not roll back writers.
        self._memory_lock = (
            asyncio.Lock()
            if engine.url.drivername.startswith("sqlite")
            and engine.url.database in (None, "", ":memory:")
            else None
        )

    # 按需开启事务并托管连接；内存数据库先加锁，finally 中确保释放。
    @asynccontextmanager
    async def _connection(self, transaction: bool) -> AsyncGenerator[AsyncConnection, None]:
        if self._memory_lock:
            await self._memory_lock.acquire()
        try:
            context = self._engine.begin() if transaction else self._engine.connect()
            async with context as connection:
                yield connection
        finally:
            if self._memory_lock:
                self._memory_lock.release()

    # 仅供测试建表，正式环境使用 Alembic 迁移。
    async def create_schema_for_tests(self) -> None:
        async with self._connection(True) as connection:
            await connection.run_sync(metadata.create_all)

    # 实际查询调查表和方案表，确认连接可用且关键迁移已执行。
    async def check_ready(self) -> None:
        async with self._connection(False) as connection:
            await connection.execute(select(investigation.c.id).limit(1))
            await connection.execute(select(proposal_table.c.id).limit(1))

    # 启动时把遗留 RUNNING 调查标为 FAILED，使崩溃后的记录可以再次追问。
    async def recover_interrupted(self) -> None:
        # Deployment contract: one Agent process/replica per schema until distributed leases exist.
        async with self._connection(True) as connection:
            await connection.execute(
                update(investigation)
                .where(investigation.c.status == "RUNNING")
                .values(status="FAILED", updated_at=datetime.now(UTC))
            )

    # 按目标生成 UUID 和 UTC 时间，插入 RUNNING 记录并返回调查对象。
    async def create(self, goal: str) -> Investigation:
        now = datetime.now(UTC)
        investigation_id = str(uuid4())
        async with self._connection(True) as connection:
            await connection.execute(
                insert(investigation).values(
                    id=investigation_id,
                    goal=goal,
                    status="RUNNING",
                    scope=None,
                    scope_version=0,
                    tool_trajectory=[],
                    final_diagnosis=None,
                    created_at=now,
                    updated_at=now,
                )
            )
        return await self.load(investigation_id)

    # 按 ID 重建证据、假设、消息及方案；记录不存在时抛出未找到异常。
    async def load(self, investigation_id: str) -> Investigation:
        async with self._connection(False) as connection:
            row = (
                (
                    await connection.execute(
                        select(investigation).where(investigation.c.id == investigation_id)
                    )
                )
                .mappings()
                .one_or_none()
            )
            if row is None:
                raise InvestigationNotFoundError()
            evidence_rows = (
                (
                    await connection.execute(
                        select(evidence)
                        .where(evidence.c.investigation_id == investigation_id)
                        .order_by(evidence.c.collected_at, evidence.c.id)
                    )
                )
                .mappings()
                .all()
            )
            hypothesis_rows = (
                (
                    await connection.execute(
                        select(hypothesis)
                        .where(hypothesis.c.investigation_id == investigation_id)
                        .order_by(hypothesis.c.created_at, hypothesis.c.id)
                    )
                )
                .mappings()
                .all()
            )
            message_rows = (
                (
                    await connection.execute(
                        select(message)
                        .where(message.c.investigation_id == investigation_id)
                        .order_by(message.c.id)
                    )
                )
                .mappings()
                .all()
            )
            proposal_rows = (
                (
                    await connection.execute(
                        select(proposal_table)
                        .where(proposal_table.c.investigation_id == investigation_id)
                        .order_by(proposal_table.c.created_at, proposal_table.c.id)
                    )
                )
                .mappings()
                .all()
            )
        return Investigation(
            id=row["id"],
            goal=row["goal"],
            status=row["status"],
            scope=row["scope"],
            scope_version=row["scope_version"],
            created_at=_utc(row["created_at"]),
            updated_at=_utc(row["updated_at"]),
            final_diagnosis=(
                Diagnosis.model_validate(row["final_diagnosis"])
                if row["final_diagnosis"] is not None
                else None
            ),
            evidence=[
                Evidence(
                    id=item["id"],
                    tool_name=item["tool_name"],
                    source=item["source"],
                    observation=item["observation"],
                    query=item["query_json"],
                    data_version=item["data_version"],
                    warnings=item["warnings_json"],
                    trace_id=item["trace_id"],
                    collected_at=_utc(item["collected_at"]),
                    java_query_id=item["java_query_id"],
                    scope_version=item["scope_version"],
                )
                for item in evidence_rows
            ],
            hypotheses=[
                Hypothesis(
                    id=item["id"],
                    statement=item["statement"],
                    status=item["status"],
                    supporting_evidence_ids=item["supporting_evidence_ids"],
                    contradicting_evidence_ids=item["contradicting_evidence_ids"],
                    reason=item["reason"],
                    confidence=item["confidence"],
                    scope_version=item["scope_version"],
                    created_at=_utc(item["created_at"]),
                    updated_at=_utc(item["updated_at"]),
                )
                for item in hypothesis_rows
            ],
            messages=[
                AgentMessage(
                    role=item["role"],
                    content=item["content"],
                    created_at=_utc(item["created_at"]),
                    diagnosis=(
                        Diagnosis.model_validate(item["diagnosis_json"])
                        if item["diagnosis_json"] is not None
                        else None
                    ),
                )
                for item in message_rows
            ],
            tool_trajectory=row["tool_trajectory"],
            proposals=[
                ProposalRecord(
                    id=item["id"],
                    investigation_id=investigation_id,
                    owner_id=item["owner_id"],
                    proposal=CampaignProposal.model_validate(item["proposal_json"]),
                    status=item["status"],
                    draft_id=item["draft_id"],
                    scope_version=item["scope_version"],
                    created_at=_utc(item["created_at"]),
                    updated_at=_utc(item["updated_at"]),
                )
                for item in proposal_rows
            ],
        )

    # 保存方案、操作员归属和范围版本，保留方案生成与 Java 草稿创建的边界。
    async def add_proposal(self, investigation_id: str, item: ProposalRecord) -> None:
        async with self._connection(True) as connection:
            await connection.execute(
                insert(proposal_table).values(
                    id=item.id,
                    investigation_id=investigation_id,
                    owner_id=item.owner_id,
                    proposal_json=item.proposal.model_dump(mode="json"),
                    status=item.status,
                    draft_id=item.draft_id,
                    scope_version=item.scope_version,
                    created_at=item.created_at,
                    updated_at=item.updated_at,
                )
            )
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(updated_at=datetime.now(UTC))
            )

    # 读取方案及当前范围证据 ID，供 Java 再次核验引用。
    async def load_proposal(self, proposal_id: str) -> ProposalRead:
        async with self._connection(False) as connection:
            row = (await connection.execute(
                select(proposal_table).where(proposal_table.c.id == proposal_id)
            )).mappings().one_or_none()
            if row is None:
                raise InvestigationNotFoundError()
            evidence_rows = (await connection.execute(
                select(evidence.c.id).where(
                    evidence.c.investigation_id == row["investigation_id"],
                    evidence.c.scope_version == row["scope_version"],
                )
            )).scalars().all()
        record = ProposalRecord(
            id=row["id"], investigation_id=row["investigation_id"],
            owner_id=row["owner_id"],
            proposal=CampaignProposal.model_validate(row["proposal_json"]),
            status=row["status"], draft_id=row["draft_id"],
            scope_version=row["scope_version"],
            created_at=_utc(row["created_at"]), updated_at=_utc(row["updated_at"]),
        )
        return ProposalRead(record=record, evidence_ids=list(evidence_rows))

    # 锁定方案后幂等关联 Java 草稿 ID；已关联其他草稿时拒绝冲突。
    async def mark_draft_created(self, proposal_id: str, draft_id: int) -> ProposalRecord:
        async with self._connection(True) as connection:
            row = (await connection.execute(
                select(proposal_table).where(proposal_table.c.id == proposal_id).with_for_update()
            )).mappings().one_or_none()
            if row is None:
                raise InvestigationNotFoundError()
            if row["status"] not in ("GENERATED", "DRAFT_CREATED") or (
                row["draft_id"] is not None and row["draft_id"] != draft_id
            ):
                raise InvestigationConflictError("proposal_status_conflict")
            await connection.execute(update(proposal_table).where(
                proposal_table.c.id == proposal_id
            ).values(status="DRAFT_CREATED", draft_id=draft_id, updated_at=datetime.now(UTC)))
        return (await self.load_proposal(proposal_id)).record

    # 条件更新允许恢复的终态为 RUNNING，区分记录不存在与并发冲突。
    async def begin_followup(self, investigation_id: str) -> Investigation:
        async with self._connection(True) as connection:
            result = await connection.execute(
                update(investigation)
                .where(
                    investigation.c.id == investigation_id,
                    investigation.c.status.in_(
                        [
                            "COMPLETED",
                            "INSUFFICIENT_EVIDENCE",
                            "BUDGET_EXHAUSTED",
                            "FAILED",
                            "CANCELLED",
                        ]
                    ),
                )
                .values(status="RUNNING", updated_at=datetime.now(UTC))
            )
            if result.rowcount != 1:
                exists = (
                    await connection.execute(
                        select(investigation.c.id).where(investigation.c.id == investigation_id)
                    )
                ).scalar_one_or_none()
                if exists is None:
                    raise InvestigationNotFoundError()
                raise InvestigationConflictError()
        return await self.load(investigation_id)

    # 锁定调查行后追加工具名，避免并发调用覆盖轨迹。
    async def append_tool(self, investigation_id: str, name: str) -> None:
        async with self._connection(True) as connection:
            row = (
                await connection.execute(
                    select(investigation.c.tool_trajectory)
                    .where(investigation.c.id == investigation_id)
                    .with_for_update()
                )
            ).one_or_none()
            if row is None:
                raise InvestigationNotFoundError()
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(tool_trajectory=[*row[0], name], updated_at=datetime.now(UTC))
            )

    # 插入带查询元数据和范围版本的证据，并刷新调查更新时间。
    async def add_evidence(self, investigation_id: str, item: Evidence) -> None:
        async with self._connection(True) as connection:
            await connection.execute(
                insert(evidence).values(
                    id=item.id,
                    investigation_id=investigation_id,
                    tool_name=item.tool_name,
                    source=item.source,
                    observation=item.observation,
                    query_json=item.query,
                    data_version=item.data_version,
                    warnings_json=item.warnings,
                    trace_id=item.trace_id,
                    collected_at=item.collected_at,
                    java_query_id=item.java_query_id,
                    scope_version=item.scope_version,
                )
            )
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(updated_at=datetime.now(UTC))
            )

    # 保存假设、支持和反对证据、置信度及版本，供调查恢复使用。
    async def add_hypothesis(self, investigation_id: str, item: Hypothesis) -> None:
        async with self._connection(True) as connection:
            await connection.execute(
                insert(hypothesis).values(
                    id=item.id,
                    investigation_id=investigation_id,
                    statement=item.statement,
                    status=item.status,
                    supporting_evidence_ids=item.supporting_evidence_ids,
                    contradicting_evidence_ids=item.contradicting_evidence_ids,
                    reason=item.reason,
                    confidence=item.confidence,
                    scope_version=item.scope_version,
                    created_at=item.created_at,
                    updated_at=item.updated_at,
                )
            )
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(updated_at=datetime.now(UTC))
            )

    # 按调查归属和版本更新假设，拒绝不存在或版本不匹配的修改。
    async def update_hypothesis(self, investigation_id: str, item: Hypothesis) -> None:
        async with self._connection(True) as connection:
            result = await connection.execute(
                update(hypothesis)
                .where(
                    hypothesis.c.id == item.id,
                    hypothesis.c.investigation_id == investigation_id,
                    hypothesis.c.scope_version == item.scope_version,
                )
                .values(
                    status=item.status,
                    supporting_evidence_ids=item.supporting_evidence_ids,
                    contradicting_evidence_ids=item.contradicting_evidence_ids,
                    reason=item.reason,
                    confidence=item.confidence,
                    updated_at=item.updated_at,
                )
            )
            if result.rowcount != 1:
                raise InvestigationConflictError()
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(updated_at=datetime.now(UTC))
            )

    # 以旧版本作乐观锁更新范围，同事务淘汰历史假设和方案。
    async def change_scope(self, investigation_id: str, scope: str, old_version: int) -> None:
        now = datetime.now(UTC)
        async with self._connection(True) as connection:
            result = await connection.execute(
                update(investigation)
                .where(
                    investigation.c.id == investigation_id,
                    investigation.c.scope_version == old_version,
                    investigation.c.status == "RUNNING",
                )
                .values(scope=scope, scope_version=old_version + 1, updated_at=now)
            )
            if result.rowcount != 1:
                raise InvestigationConflictError()
            await connection.execute(
                update(hypothesis)
                .where(
                    hypothesis.c.investigation_id == investigation_id,
                    hypothesis.c.scope_version <= old_version,
                    hypothesis.c.status != "REJECTED",
                )
                .values(status="REJECTED", reason="superseded_by_scope_change", updated_at=now)
            )

    # 保存会话消息和可选诊断，并刷新调查更新时间。
    async def add_message(self, investigation_id: str, item: AgentMessage) -> None:
        async with self._connection(True) as connection:
            await connection.execute(
                insert(message).values(
                    investigation_id=investigation_id,
                    role=item.role,
                    content=item.content,
                    created_at=item.created_at,
                    diagnosis_json=(
                        item.diagnosis.model_dump(mode="json")
                        if item.diagnosis is not None
                        else None
                    ),
                )
            )
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(updated_at=datetime.now(UTC))
            )

    # 同事务更新 RUNNING 调查为终态，写入诊断与助手消息。
    async def finish(
        self, investigation_id: str, status: InvestigationStatus, diagnosis: Diagnosis
    ) -> None:
        now = datetime.now(UTC)
        async with self._connection(True) as connection:
            result = await connection.execute(
                update(investigation)
                .where(
                    investigation.c.id == investigation_id,
                    investigation.c.status == "RUNNING",
                )
                .values(
                    status=status, final_diagnosis=diagnosis.model_dump(mode="json"), updated_at=now
                )
            )
            if result.rowcount != 1:
                raise InvestigationConflictError()
            await connection.execute(
                insert(message).values(
                    investigation_id=investigation_id,
                    role="ASSISTANT",
                    content=diagnosis.summary,
                    created_at=now,
                    diagnosis_json=diagnosis.model_dump(mode="json"),
                )
            )
