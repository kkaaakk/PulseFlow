"""Migrations run only against the separate Agent database URL."""

import asyncio
import os
from logging.config import fileConfig

from alembic import context
from dotenv import load_dotenv
from sqlalchemy.ext.asyncio import create_async_engine

from pulseflow_agent.workspace.models import metadata

config = context.config
if config.config_file_name:
    fileConfig(config.config_file_name)
load_dotenv()
database_url = os.getenv("PULSEFLOW_AGENT_DATABASE_URL")
if not database_url:
    raise RuntimeError("PULSEFLOW_AGENT_DATABASE_URL is required for migrations")


# 离线配置 Alembic 并生成迁移 SQL，不创建实际数据库连接。
def run_migrations_offline() -> None:
    context.configure(url=database_url, target_metadata=metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()


# 在给定同步连接中绑定元数据并执行事务化迁移。
def do_run_migrations(connection: object) -> None:
    context.configure(connection=connection, target_metadata=metadata)
    with context.begin_transaction():
        context.run_migrations()


# 创建异步迁移引擎，通过同步桥执行 Alembic，结束后释放连接池。
async def run_async_migrations() -> None:
    engine = create_async_engine(database_url, pool_pre_ping=True)
    try:
        async with engine.connect() as connection:
            await connection.run_sync(do_run_migrations)
    finally:
        await engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_async_migrations())
