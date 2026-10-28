"""No real model provider may be contacted by ordinary tests or CI."""

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from pydantic_ai import models

models.ALLOW_MODEL_REQUESTS = False


@pytest.fixture(autouse=True)
def offline_test_environment(
    monkeypatch: pytest.MonkeyPatch, request: pytest.FixtureRequest
) -> None:
    """Keep ordinary tests isolated from real credentials in the local .env."""
    if request.node.get_closest_marker("real_provider"):
        return
    monkeypatch.setenv("PULSEFLOW_AGENT_ENV", "test")
    monkeypatch.setenv("PULSEFLOW_AGENT_MODEL", "test")


# 为每个测试创建独立 SQLite 文件地址，防止用例共享调查状态。
@pytest.fixture
def database_url(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> str:
    url = f"sqlite+aiosqlite:///{(tmp_path / 'agent.db').as_posix()}"
    monkeypatch.setenv("PULSEFLOW_AGENT_DATABASE_URL", url)
    command.upgrade(Config("alembic.ini"), "head")
    return url
