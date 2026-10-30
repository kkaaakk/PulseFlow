"""Persist evidence-backed campaign proposals and Java draft references."""

import sqlalchemy as sa
from alembic import op

revision = "0002_campaign_proposals"
down_revision = "0001_agent_workspace"
branch_labels = None
depends_on = None


# 新增与调查关联的 Campaign 提案表，保存提案、草稿快照及范围版本。
def upgrade() -> None:
    op.create_table(
        "agent_campaign_proposal",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column(
            "investigation_id",
            sa.String(36),
            sa.ForeignKey("agent_investigation.id"),
            nullable=False,
        ),
        sa.Column("proposal_json", sa.JSON(), nullable=False),
        sa.Column("draft_json", sa.JSON(), nullable=False),
        sa.Column("scope_version", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index(
        "idx_agent_proposal_investigation",
        "agent_campaign_proposal",
        ["investigation_id", "created_at"],
    )


# 删除提案表并撤销本次迁移创建的提案持久化结构。
def downgrade() -> None:
    op.drop_index("idx_agent_proposal_investigation", table_name="agent_campaign_proposal")
    op.drop_table("agent_campaign_proposal")
