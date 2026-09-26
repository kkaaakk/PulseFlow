"""Agent-owned schema only; no PulseFlow business tables or credentials."""

from sqlalchemy import (
    JSON,
    Column,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
)

metadata = MetaData()

investigation = Table(
    "agent_investigation", metadata,
    Column("id", String(36), primary_key=True),
    Column("goal", Text, nullable=False),
    Column("status", String(32), nullable=False),
    Column("scope", Text),
    Column("scope_version", Integer, nullable=False, default=0),
    Column("tool_trajectory", JSON, nullable=False, default=list),
    Column("final_diagnosis", JSON),
    Column("created_at", DateTime(timezone=True), nullable=False),
    Column("updated_at", DateTime(timezone=True), nullable=False),
)

evidence = Table(
    "agent_evidence", metadata,
    Column("id", String(36), primary_key=True),
    Column("investigation_id", String(36), ForeignKey("agent_investigation.id"), nullable=False),
    Column("tool_name", String(64), nullable=False),
    Column("source", String(64), nullable=False),
    Column("observation", Text, nullable=False),
    Column("query_json", JSON, nullable=False),
    Column("data_version", String(128)),
    Column("warnings_json", JSON, nullable=False),
    Column("trace_id", String(32)),
    Column("collected_at", DateTime(timezone=True), nullable=False),
    Column("java_query_id", String(36), nullable=False),
    Column("scope_version", Integer, nullable=False),
    Index("idx_agent_evidence_investigation", "investigation_id", "collected_at"),
)

hypothesis = Table(
    "agent_hypothesis", metadata,
    Column("id", String(36), primary_key=True),
    Column("investigation_id", String(36), ForeignKey("agent_investigation.id"), nullable=False),
    Column("statement", Text, nullable=False),
    Column("status", String(16), nullable=False),
    Column("supporting_evidence_ids", JSON, nullable=False),
    Column("contradicting_evidence_ids", JSON, nullable=False),
    Column("reason", Text),
    Column("confidence", String(8)),
    Column("scope_version", Integer, nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False),
    Column("updated_at", DateTime(timezone=True), nullable=False),
    Index("idx_agent_hypothesis_investigation", "investigation_id", "created_at"),
)

message = Table(
    "agent_message", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("investigation_id", String(36), ForeignKey("agent_investigation.id"), nullable=False),
    Column("role", String(16), nullable=False),
    Column("content", Text, nullable=False),
    Column("diagnosis_json", JSON),
    Column("created_at", DateTime(timezone=True), nullable=False),
    Index("idx_agent_message_investigation", "investigation_id", "id"),
)
