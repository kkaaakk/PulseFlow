"""Only business API, per-run workspace and trusted operator context reach tools."""

from dataclasses import dataclass, field

from pydantic import SecretStr

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


@dataclass(frozen=True)
class OperatorContext:
    operator_id: int
    role: str


@dataclass(frozen=True)
class DraftAuthorization:
    investigation_id: str
    grant: SecretStr
    promotion_facts: list[PromotionFact] = field(default_factory=list)


@dataclass
class AgentDependencies:
    pulseflow: PulseFlowApiClient
    workspace: InvestigationWorkspace
    operator_context: OperatorContext | None
    guardrail: AzurePiiGuardrail
    draft_authorization: DraftAuthorization | None = None
    draft_created: bool = False
