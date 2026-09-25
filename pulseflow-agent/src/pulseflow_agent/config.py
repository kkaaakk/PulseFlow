"""Environment-backed service configuration."""

from typing import Literal

from pydantic import Field, HttpUrl, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AgentSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    pulseflow_agent_env: Literal["development", "test", "production"] = "development"
    pulseflow_agent_host: str = "127.0.0.1"
    pulseflow_agent_port: int = Field(default=8001, ge=1, le=65535)
    pulseflow_agent_model: str = "test"
    pulseflow_agent_api_key: SecretStr | None = None
    pulseflow_agent_base_url: HttpUrl | None = None
    pulseflow_java_base_url: HttpUrl
    pulseflow_agent_internal_token: SecretStr | None = None
    pulseflow_agent_max_model_requests: int = Field(default=8, ge=1)
    pulseflow_agent_max_tool_calls: int = Field(default=12, ge=0)
    pulseflow_agent_max_input_tokens: int = Field(default=12000, ge=1)
    pulseflow_agent_max_cost_usd: float = Field(default=0.5, gt=0)
    azure_language_endpoint: HttpUrl | None = None
    azure_language_key: SecretStr | None = None
    azure_language_pii_language: str = "zh-hans"

    @property
    def is_test_model(self) -> bool:
        return self.pulseflow_agent_model == "test"

    @model_validator(mode="after")
    def validate_real_model(self) -> "AgentSettings":
        if self.is_test_model:
            if self.pulseflow_agent_env == "production":
                raise ValueError("production requires a real model")
            return self
        if not self.pulseflow_agent_model.startswith("openai:"):
            raise ValueError("unsupported model provider")
        if not self.pulseflow_agent_api_key or not self.pulseflow_agent_api_key.get_secret_value():
            raise ValueError("real model API key is required")
        if not self.azure_language_endpoint or not self.azure_language_key:
            raise ValueError("real model requires Azure PII configuration")
        if not self.azure_language_key.get_secret_value():
            raise ValueError("real model requires Azure PII configuration")
        if self.azure_language_endpoint.scheme != "https":
            raise ValueError("Azure PII endpoint requires HTTPS")
        if self.pulseflow_agent_env == "production" and (
            not self.pulseflow_agent_internal_token
            or not self.pulseflow_agent_internal_token.get_secret_value()
        ):
            raise ValueError("production requires internal token")
        return self
