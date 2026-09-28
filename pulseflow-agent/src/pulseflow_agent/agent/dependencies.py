"""Only business API, per-run workspace and trusted operator context reach tools."""

from dataclasses import dataclass, field

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


@dataclass(frozen=True)
class OperatorContext:
    operator_id: int
    role: str


@dataclass(frozen=True)
class ProposalContext:
    investigation_id: str
    owner_id: int
    promotion_facts: list[PromotionFact] = field(default_factory=list)


@dataclass
class AgentDependencies:
    pulseflow: PulseFlowApiClient
    workspace: InvestigationWorkspace
    operator_context: OperatorContext | None
    guardrail: AzurePiiGuardrail
    proposal_context: ProposalContext | None = None
    proposal_created: bool = False
