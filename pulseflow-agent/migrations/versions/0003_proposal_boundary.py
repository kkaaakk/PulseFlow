"""Keep proposals independently of Java drafts and bind them to their owner."""

import json

import sqlalchemy as sa
from alembic import op

revision = "0003_proposal_boundary"
down_revision = "0002_campaign_proposals"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("agent_campaign_proposal", sa.Column("owner_id", sa.BigInteger(), nullable=True))
    op.add_column(
        "agent_campaign_proposal",
        sa.Column("status", sa.String(24), nullable=False, server_default="GENERATED"),
    )
    op.add_column("agent_campaign_proposal", sa.Column("draft_id", sa.BigInteger()))
    op.add_column("agent_campaign_proposal", sa.Column("updated_at", sa.DateTime(timezone=True)))
    # Old rows lack a durable owner. Preserve their Draft link but quarantine them.
    connection = op.get_bind()
    rows = connection.execute(sa.text(
        "SELECT id, draft_json FROM agent_campaign_proposal"
    )).mappings().all()
    for row in rows:
        draft = row["draft_json"]
        if isinstance(draft, str):
            draft = json.loads(draft)
        draft_id = draft.get("draft_id") if isinstance(draft, dict) else None
        connection.execute(sa.text(
            "UPDATE agent_campaign_proposal SET status='CANCELLED', "
            "draft_id=:draft_id, updated_at=created_at WHERE id=:id"
        ), {"draft_id": draft_id, "id": row["id"]})
    op.drop_column("agent_campaign_proposal", "draft_json")


def downgrade() -> None:
    op.add_column("agent_campaign_proposal", sa.Column("draft_json", sa.JSON()))
    op.drop_column("agent_campaign_proposal", "updated_at")
    op.drop_column("agent_campaign_proposal", "draft_id")
    op.drop_column("agent_campaign_proposal", "status")
    op.drop_column("agent_campaign_proposal", "owner_id")
