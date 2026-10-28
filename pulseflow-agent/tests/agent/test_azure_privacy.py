import json

import httpx
import pytest

from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from tests.test_foundation import azure_response, settings


# 验证 Azure 请求显式关闭服务端输入日志。
@pytest.mark.asyncio
async def test_azure_guardrail_explicitly_disables_service_input_logging() -> None:
    requests: list[httpx.Request] = []

    # 模拟当前用例的 HTTP 响应并检查请求约定，隔离外部 Azure 或 Java 服务。
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=azure_response())

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        await AzurePiiGuardrail(settings(real=True), client).check("活动效果如何")
    assert len(requests) == 1
    parameters = json.loads(requests[0].content)["parameters"]
    assert parameters["loggingOptOut"] is True
