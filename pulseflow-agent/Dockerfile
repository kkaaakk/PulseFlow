FROM ghcr.io/astral-sh/uv:0.11.0 AS uv
FROM python:3.11.15-slim-bookworm
COPY --from=uv /uv /usr/local/bin/uv
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 agent && useradd --uid 10001 --gid agent --create-home agent
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_NO_CACHE=1 PYTHONUNBUFFERED=1
COPY pyproject.toml uv.lock ./
COPY src ./src
COPY migrations ./migrations
COPY alembic.ini ./
RUN uv sync --locked --no-dev && chown -R agent:agent /app
USER 10001:10001
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8001
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health/ready', timeout=2)"
STOPSIGNAL SIGTERM
CMD ["uvicorn", "pulseflow_agent.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1", "--timeout-graceful-shutdown", "15", "--no-access-log"]
