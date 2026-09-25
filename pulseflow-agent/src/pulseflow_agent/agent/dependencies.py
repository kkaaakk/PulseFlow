"""Only business API, per-run workspace and trusted operator context reach tools."""

from dataclasses import dataclass

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.investigation import InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


@dataclass(frozen=True)
class OperatorContext:
    operator_id: int
    role: str


@dataclass
class AgentDependencies:
    pulseflow: PulseFlowApiClient
    workspace: InvestigationWorkspace
    operator_context: OperatorContext | None
    guardrail: AzurePiiGuardrail
