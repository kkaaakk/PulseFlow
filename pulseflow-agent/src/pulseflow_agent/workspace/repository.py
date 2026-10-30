"""Repository contract and SQLAlchemy implementation for Agent-owned state."""

import asyncio
from collections.abc import AsyncIterator
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


class InvestigationNotFoundError(Exception):
    pass


class InvestigationConflictError(Exception):
    pass


class InvestigationRepository(Protocol):
    # 持久化指定调查的提案记录，调用方须先验证当前范围证据及操作员授权。
    async def add_proposal(self, investigation_id: str, item: ProposalRecord) -> None: ...
    # 读取提案及其范围内的证据 ID，缺失时抛出 InvestigationNotFoundError。
    async def load_proposal(self, proposal_id: str) -> ProposalRead: ...
    # 将提案幂等绑定至 Java 草稿；不兼容的状态或不同草稿绑定应报冲突。
    async def mark_draft_created(self, proposal_id: str, draft_id: int) -> ProposalRecord: ...
    # 以 goal 创建 RUNNING 调查并返回其持久化快照。
    async def create(self, goal: str) -> Investigation: ...
    # 读取调查及其证据、假设、可见消息、工具轨迹和提案；缺失时应报错。
    async def load(self, investigation_id: str) -> Investigation: ...
    # 将已结束调查原子切换为 RUNNING，已有运行时拒绝并发后续轮次。
    async def begin_followup(self, investigation_id: str) -> Investigation: ...
    # 向指定调查的持久化工具轨迹追加一次工具调用。
    async def append_tool(self, investigation_id: str, name: str) -> None: ...
    # 保存指定调查的一条来源可追溯证据。
    async def add_evidence(self, investigation_id: str, item: Evidence) -> None: ...
    # 保存属于指定调查和范围版本的假设。
    async def add_hypothesis(self, investigation_id: str, item: Hypothesis) -> None: ...
    # 按调查和范围约束更新已有假设，不允许跨调查写入。
    async def update_hypothesis(self, investigation_id: str, item: Hypothesis) -> None: ...
    # 以 old_version 为乐观锁更新范围并递增版本，将旧范围假设标记为历史失效。
    async def change_scope(self, investigation_id: str, scope: str, old_version: int) -> None: ...
    # 追加用户可见消息及可选诊断，持久化过程不保存隐藏推理。
    async def add_message(self, investigation_id: str, item: AgentMessage) -> None: ...
    # 原子保存调查终态、最终诊断及对应助手消息，非运行态不应重复结束。
    async def finish(
        self, investigation_id: str, status: InvestigationStatus, diagnosis: Diagnosis
    ) -> None: ...


# 将无时区数据库时间解释为 UTC，有时区时间转换为 UTC，统一快照的时间语义。
def _utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


class SqlAlchemyInvestigationRepository:
    # 保存异步引擎；内存 SQLite 额外使用互斥锁，避免共享连接的读事务回滚并发写入。
    def __init__(self, engine: AsyncEngine) -> None:
        self._engine = engine
        # In-memory SQLite shares one connection; concurrent readers must not roll back writers.
        self._memory_lock = (
            asyncio.Lock()
            if engine.url.drivername.startswith("sqlite")
            and engine.url.database in (None, "", ":memory:")
            else None
        )

    # 按 transaction 选择事务或普通连接；内存 SQLite 串行访问，并在 finally 中释放互斥锁。
    @asynccontextmanager
    async def _connection(self, transaction: bool) -> AsyncIterator[AsyncConnection]:
        if self._memory_lock:
            await self._memory_lock.acquire()
        try:
            context = self._engine.begin() if transaction else self._engine.connect()
            async with context as connection:
                yield connection
        finally:
            if self._memory_lock:
                self._memory_lock.release()

    # 仅供测试使用，在事务中按 SQLAlchemy metadata 建表；正常部署由 Alembic 管理结构。
    async def create_schema_for_tests(self) -> None:
        async with self._connection(True) as connection:
            await connection.run_sync(metadata.create_all)

    # 探测调查表与提案表是否可查询，结构缺失或依赖故障直接向调用方传播。
    async def check_ready(self) -> None:
        async with self._connection(False) as connection:
            await connection.execute(select(investigation.c.id).limit(1))
            await connection.execute(select(proposal_table.c.id).limit(1))

    # 启动时将遗留 RUNNING 调查标记 FAILED；依赖每个 schema 仅部署一个 Agent 进程的约定。
    async def recover_interrupted(self) -> None:
        # Deployment contract: one Agent process/replica per schema until distributed leases exist.
        async with self._connection(True) as connection:
            await connection.execute(
                update(investigation)
                .where(investigation.c.status == "RUNNING")
                .values(status="FAILED", updated_at=datetime.now(UTC))
            )

    # 生成 UUID 和 UTC 时间，在事务中写入初始 RUNNING 调查，再重新加载完整快照。
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

    # 按稳定顺序读取调查相关记录并恢复类型化领域对象，统一时间为 UTC；调查不存在时抛出专用异常。
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

    # 在同一事务中插入类型化提案 JSON 和所属范围、操作员信息，并刷新调查更新时间。
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

    # 读取提案和与其 scope_version 一致的证据 ID，组成供 Java 验证的 ProposalRead。
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

    # 锁定提案后只允许 GENERATED 或同草稿的 DRAFT_CREATED 更新；重复同 ID 幂等，
    # 状态或草稿不符时拒绝。
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

    # 以可恢复终态为条件原子更新 RUNNING；未命中时区分调查缺失与正在运行，成功后重载快照。
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

    # 锁定调查行读取并追加工具轨迹，避免并发追加覆盖，并更新时间。
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

    # 在事务中保存证据查询、来源、版本和 trace 信息，并刷新所属调查更新时间。
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

    # 在事务中保存假设状态、证据引用和范围版本，并刷新所属调查更新时间。
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

    # 按假设 ID、调查 ID 和范围版本更新状态与引用；未唯一命中时抛出冲突，成功同步调查时间。
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

    # 以 RUNNING 和旧范围版本为条件更新新范围，同时在事务中否决旧版本假设；版本竞争时抛出冲突。
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

    # 保存可见消息和可选诊断 JSON，并在同一事务中刷新调查时间。
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

    # 只结束 RUNNING 调查，并在同一事务中保存最终诊断和助手消息；未命中时拒绝重复或竞争结束。
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
