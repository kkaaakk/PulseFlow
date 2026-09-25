"""Minimal typed agent runtime. Business tools arrive in a later phase."""

from opentelemetry import trace
from pydantic import BaseModel
from pydantic_ai import Agent
from pydantic_ai.models.openai import OpenAIModel
from pydantic_ai.models.test import TestModel
from pydantic_ai.providers.openai import OpenAIProvider
from pydantic_ai.usage import UsageLimits

from pulseflow_agent.config import AgentSettings
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


class FoundationResult(BaseModel):
    summary: str


def create_model(settings: AgentSettings) -> TestModel | OpenAIModel:
    if settings.is_test_model:
        return TestModel()
    api_key = settings.pulseflow_agent_api_key
    assert api_key is not None  # validated by AgentSettings
    provider = OpenAIProvider(
        api_key=api_key.get_secret_value(),
        base_url=(
            str(settings.pulseflow_agent_base_url)
            if settings.pulseflow_agent_base_url else None
        ),
    )
    return OpenAIModel(settings.pulseflow_agent_model.removeprefix("openai:"), provider=provider)


def create_usage_limits(settings: AgentSettings) -> UsageLimits:
    return UsageLimits(
        request_limit=settings.pulseflow_agent_max_model_requests,
        tool_calls_limit=settings.pulseflow_agent_max_tool_calls,
        input_tokens_limit=settings.pulseflow_agent_max_input_tokens,
    )


class GrowthInvestigator:
    def __init__(self, settings: AgentSettings, guardrail: AzurePiiGuardrail) -> None:
        self._settings = settings
        self._guardrail = guardrail
        self._agent: Agent[None, FoundationResult] = Agent(
            create_model(settings),
            output_type=FoundationResult,
            instructions=(
                "You are the foundation of a growth investigator. No business tools are available. "
                "Do not claim to have inspected business data. Return a brief summary."
            ),
        )

    async def run(self, prompt: str) -> FoundationResult:
        with trace.get_tracer(__name__).start_as_current_span("agent.run") as span:
            span.set_attribute(
                "agent.model_provider", "test" if self._settings.is_test_model else "openai"
            )
            await self._guardrail.check(prompt)
            result = await self._agent.run(prompt, usage_limits=create_usage_limits(self._settings))
            return result.output
