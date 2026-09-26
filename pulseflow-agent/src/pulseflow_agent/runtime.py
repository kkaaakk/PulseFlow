"""Single-process admission limits; no prompts or credentials in runtime logs."""

import asyncio
import json
import logging
from collections import deque
from time import monotonic

from pulseflow_agent.config import AgentSettings

logger = logging.getLogger("pulseflow_agent.runtime")
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)
logger.propagate = False


class AdmissionRejected(Exception):
    pass


class RunAdmission:
    def __init__(self, settings: AgentSettings) -> None:
        self.settings = settings
        self.active = 0
        self._starts: deque[float] = deque()
        self._lock = asyncio.Lock()
        self.closing = False

    async def acquire(self) -> None:
        async with self._lock:
            now = monotonic()
            while self._starts and now - self._starts[0] >= 60:
                self._starts.popleft()
            if (
                self.closing
                or self.active >= self.settings.pulseflow_agent_max_concurrency
                or len(self._starts) >= self.settings.pulseflow_agent_runs_per_minute
            ):
                raise AdmissionRejected("agent_capacity_exceeded")
            self._starts.append(now)
            self.active += 1

    def release(self, status: str, started: float) -> None:
        self.active -= 1
        logger.info(
            json.dumps(
                {
                    "event": "agent_run_finished",
                    "status": status,
                    "duration_ms": round((monotonic() - started) * 1000),
                }
            )
        )
