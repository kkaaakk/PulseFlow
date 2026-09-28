"""Historical Proposal rows remain auditable but cannot create new drafts."""

import json
import sqlite3
from pathlib import Path
from uuid import uuid4

import pytest
from alembic import command
from alembic.config import Config


def test_old_proposal_is_quarantined_with_its_draft_link(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    database = tmp_path / "legacy.db"
    monkeypatch.setenv(
        "PULSEFLOW_AGENT_DATABASE_URL", f"sqlite+aiosqlite:///{database.as_posix()}"
    )
    config = Config("alembic.ini")
    command.upgrade(config, "0002_campaign_proposals")
    investigation_id, proposal_id = str(uuid4()), str(uuid4())
    with sqlite3.connect(database) as connection:
        connection.execute(
            "INSERT INTO agent_investigation "
            "(id,goal,status,scope,scope_version,tool_trajectory,"
            "final_diagnosis,created_at,updated_at) "
            "VALUES (?,?,?,?,?,?,?,?,?)",
            (investigation_id, "historical", "COMPLETED", None, 0, "[]", None,
             "2026-09-01 00:00:00", "2026-09-01 00:00:00"),
        )
        connection.execute(
            "INSERT INTO agent_campaign_proposal "
            "(id,investigation_id,proposal_json,draft_json,scope_version,created_at) "
            "VALUES (?,?,?,?,?,?)",
            (proposal_id, investigation_id, json.dumps({"campaign_name": "historical"}),
             json.dumps({"draft_id": 77}), 0, "2026-09-01 00:00:00"),
        )
    command.upgrade(config, "head")
    with sqlite3.connect(database) as connection:
        assert connection.execute(
            "SELECT owner_id,status,draft_id FROM agent_campaign_proposal WHERE id=?",
            (proposal_id,),
        ).fetchone() == (None, "CANCELLED", 77)
