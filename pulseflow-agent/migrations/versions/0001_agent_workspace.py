"""Create Agent-owned investigation, evidence, hypothesis and message tables."""

import sqlalchemy as sa
from alembic import op

revision = "0001_agent_workspace"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "agent_investigation",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("goal", sa.Text(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("scope", sa.Text()),
        sa.Column("scope_version", sa.Integer(), nullable=False),
        sa.Column("tool_trajectory", sa.JSON(), nullable=False),
        sa.Column("final_diagnosis", sa.JSON()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "agent_evidence",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("investigation_id", sa.String(36),
                  sa.ForeignKey("agent_investigation.id"), nullable=False),
        sa.Column("tool_name", sa.String(64), nullable=False),
        sa.Column("source", sa.String(64), nullable=False),
        sa.Column("observation", sa.Text(), nullable=False),
        sa.Column("query_json", sa.JSON(), nullable=False),
        sa.Column("data_version", sa.String(128)),
        sa.Column("warnings_json", sa.JSON(), nullable=False),
        sa.Column("trace_id", sa.String(32)),
        sa.Column("collected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("java_query_id", sa.String(36), nullable=False),
        sa.Column("scope_version", sa.Integer(), nullable=False),
    )
    op.create_index("idx_agent_evidence_investigation", "agent_evidence",
                    ["investigation_id", "collected_at"])
    op.create_table(
        "agent_hypothesis",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("investigation_id", sa.String(36),
                  sa.ForeignKey("agent_investigation.id"), nullable=False),
        sa.Column("statement", sa.Text(), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("supporting_evidence_ids", sa.JSON(), nullable=False),
        sa.Column("contradicting_evidence_ids", sa.JSON(), nullable=False),
        sa.Column("reason", sa.Text()),
        sa.Column("confidence", sa.String(8)),
        sa.Column("scope_version", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("idx_agent_hypothesis_investigation", "agent_hypothesis",
                    ["investigation_id", "created_at"])
    op.create_table(
        "agent_message",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("investigation_id", sa.String(36),
                  sa.ForeignKey("agent_investigation.id"), nullable=False),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("diagnosis_json", sa.JSON()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("idx_agent_message_investigation", "agent_message",
                    ["investigation_id", "id"])


def downgrade() -> None:
    op.drop_index("idx_agent_message_investigation", table_name="agent_message")
    op.drop_table("agent_message")
    op.drop_index("idx_agent_hypothesis_investigation", table_name="agent_hypothesis")
    op.drop_table("agent_hypothesis")
    op.drop_index("idx_agent_evidence_investigation", table_name="agent_evidence")
    op.drop_table("agent_evidence")
    op.drop_table("agent_investigation")
