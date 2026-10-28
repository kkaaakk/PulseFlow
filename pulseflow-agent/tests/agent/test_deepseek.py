"""Exercise DeepSeek thinking/tool wire compatibility without external requests."""

import json
from typing import Any

import httpx
import pytest
from pydantic import BaseModel, SecretStr, ValidationError
from pydantic_ai import Agent, models

from pulseflow_agent.agent.growth_investigator import create_model
from pulseflow_agent.config import AgentSettings


def settings(**overrides: object) -> AgentSettings:
    values: dict[str, Any] = {
        "_env_file": None,
        "pulseflow_agent_env": "test",
        "pulseflow_agent_model": "deepseek:deepseek-flash",
        "pulseflow_agent_base_url": "https://api.deepseek.com",
        "pulseflow_agent_api_key": SecretStr("fake-model-key"),
        "pulseflow_java_base_url": "http://java.internal:8080",
        "pulseflow_agent_database_url": SecretStr("sqlite+aiosqlite:///:memory:"),
        "azure_language_endpoint": "https://example.cognitiveservices.azure.com",
        "azure_language_key": SecretStr("fake-azure-key"),
    }
    values.update(overrides)
    return AgentSettings(**values)


def test_legacy_deepseek_spelling_migrates_and_other_providers_are_rejected() -> None:
    assert settings(pulseflow_agent_model="openai:deepseek-flash").pulseflow_agent_model == (
        "deepseek:deepseek-flash"
    )
    with pytest.raises(ValidationError, match="DeepSeek provider"):
        settings(pulseflow_agent_model="openai:gpt-4o")
    with pytest.raises(ValidationError, match="DeepSeek HTTPS endpoint"):
        settings(pulseflow_agent_base_url="https://api.openai.com/v1")


class Answer(BaseModel):
    message: str


@pytest.mark.asyncio
async def test_thinking_mode_uses_auto_tools_and_preserves_reasoning_content(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requests: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.deepseek.com"
        payload = json.loads(request.content)
        requests.append(payload)
        assert payload["tool_choice"] == "auto"
        if len(requests) == 1:
            name, arguments = "count", {}
        else:
            assistant = next(item for item in payload["messages"] if item["role"] == "assistant")
            assert assistant["reasoning_content"] == "synthetic reasoning fixture"
            name, arguments = "final_result", {"message": "verified"}
        return httpx.Response(200, json={
            "id": "completion-fixture", "object": "chat.completion", "created": 1,
            "model": "deepseek-flash",
            "choices": [{"index": 0, "finish_reason": "tool_calls", "message": {
                "role": "assistant", "content": None,
                "reasoning_content": "synthetic reasoning fixture",
                "tool_calls": [{"id": f"call-{len(requests)}", "type": "function",
                                "function": {"name": name, "arguments": json.dumps(arguments)}}],
            }}],
            "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
        })

    monkeypatch.setattr(models, "ALLOW_MODEL_REQUESTS", True)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        model = create_model(settings(), client)
        agent = Agent(model, output_type=Answer)

        @agent.tool_plain
        def count() -> int:
            return 0

        result = await agent.run("Count and return a typed answer")
    assert result.output.message == "verified"
    assert len(requests) == 2
