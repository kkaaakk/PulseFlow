"""No real model provider may be contacted by ordinary tests or CI."""

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from pydantic_ai import models

models.ALLOW_MODEL_REQUESTS = False


@pytest.fixture
def database_url(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> str:
    url = f"sqlite+aiosqlite:///{(tmp_path / 'agent.db').as_posix()}"
    monkeypatch.setenv("PULSEFLOW_AGENT_DATABASE_URL", url)
    command.upgrade(Config("alembic.ini"), "head")
    return url
