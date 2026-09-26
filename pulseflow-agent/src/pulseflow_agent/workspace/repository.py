"""Repository contract and SQLAlchemy implementation for Agent-owned state."""

from datetime import UTC, datetime
from typing import Protocol
from uuid import uuid4

from sqlalchemy import insert, select, update
from sqlalchemy.ext.asyncio import AsyncEngine

from pulseflow_agent.domain.campaign_proposal import (
    CampaignDraftResponse,
    CampaignProposal,
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
    async def add_proposal(self, investigation_id: str, item: ProposalRecord) -> None: ...
    async def create(self, goal: str) -> Investigation: ...
    async def load(self, investigation_id: str) -> Investigation: ...
    async def begin_followup(self, investigation_id: str) -> Investigation: ...
    async def append_tool(self, investigation_id: str, name: str) -> None: ...
    async def add_evidence(self, investigation_id: str, item: Evidence) -> None: ...
    async def add_hypothesis(self, investigation_id: str, item: Hypothesis) -> None: ...
    async def update_hypothesis(self, investigation_id: str, item: Hypothesis) -> None: ...
    async def change_scope(self, investigation_id: str, scope: str, old_version: int) -> None: ...
    async def add_message(self, investigation_id: str, item: AgentMessage) -> None: ...
    async def finish(
        self, investigation_id: str, status: InvestigationStatus, diagnosis: Diagnosis
    ) -> None: ...


def _utc(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


class SqlAlchemyInvestigationRepository:
    def __init__(self, engine: AsyncEngine) -> None:
        self._engine = engine

    async def create_schema_for_tests(self) -> None:
        async with self._engine.begin() as connection:
            await connection.run_sync(metadata.create_all)

    async def check_ready(self) -> None:
        async with self._engine.connect() as connection:
            await connection.execute(select(investigation.c.id).limit(1))
            await connection.execute(select(proposal_table.c.id).limit(1))

    async def create(self, goal: str) -> Investigation:
        now = datetime.now(UTC)
        investigation_id = str(uuid4())
        async with self._engine.begin() as connection:
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

    async def load(self, investigation_id: str) -> Investigation:
        async with self._engine.connect() as connection:
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
                    proposal=CampaignProposal.model_validate(item["proposal_json"]),
                    draft=CampaignDraftResponse.model_validate(item["draft_json"]),
                    scope_version=item["scope_version"],
                    created_at=_utc(item["created_at"]),
                )
                for item in proposal_rows
            ],
        )

    async def add_proposal(self, investigation_id: str, item: ProposalRecord) -> None:
        async with self._engine.begin() as connection:
            await connection.execute(
                insert(proposal_table).values(
                    id=item.id,
                    investigation_id=investigation_id,
                    proposal_json=item.proposal.model_dump(mode="json"),
                    draft_json=item.draft.model_dump(mode="json"),
                    scope_version=item.scope_version,
                    created_at=item.created_at,
                )
            )
            await connection.execute(
                update(investigation)
                .where(investigation.c.id == investigation_id)
                .values(updated_at=datetime.now(UTC))
            )

    async def begin_followup(self, investigation_id: str) -> Investigation:
        async with self._engine.begin() as connection:
            result = await connection.execute(
                update(investigation)
                .where(
                    investigation.c.id == investigation_id,
                    investigation.c.status.in_(
                        ["COMPLETED", "INSUFFICIENT_EVIDENCE", "BUDGET_EXHAUSTED", "FAILED"]
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

    async def append_tool(self, investigation_id: str, name: str) -> None:
        async with self._engine.begin() as connection:
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

    async def add_evidence(self, investigation_id: str, item: Evidence) -> None:
        async with self._engine.begin() as connection:
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

    async def add_hypothesis(self, investigation_id: str, item: Hypothesis) -> None:
        async with self._engine.begin() as connection:
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

    async def update_hypothesis(self, investigation_id: str, item: Hypothesis) -> None:
        async with self._engine.begin() as connection:
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

    async def change_scope(self, investigation_id: str, scope: str, old_version: int) -> None:
        now = datetime.now(UTC)
        async with self._engine.begin() as connection:
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

    async def add_message(self, investigation_id: str, item: AgentMessage) -> None:
        async with self._engine.begin() as connection:
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

    async def finish(
        self, investigation_id: str, status: InvestigationStatus, diagnosis: Diagnosis
    ) -> None:
        now = datetime.now(UTC)
        async with self._engine.begin() as connection:
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
