import json

import httpx
import pytest

from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from tests.test_foundation import azure_response, settings


@pytest.mark.asyncio
async def test_azure_guardrail_explicitly_disables_service_input_logging() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=azure_response())

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        await AzurePiiGuardrail(settings(real=True), client).check("活动效果如何")
    assert len(requests) == 1
    parameters = json.loads(requests[0].content)["parameters"]
    assert parameters["loggingOptOut"] is True
