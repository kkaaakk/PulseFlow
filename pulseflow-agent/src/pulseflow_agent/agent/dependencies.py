# 定义每轮调查注入工具的身份、方案授权与业务依赖。
"""Only business API, per-run workspace and trusted operator context reach tools."""

from dataclasses import dataclass, field

from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from pulseflow_agent.workspace.context import ContextBuilder


# 本轮操作员上下文，用于表示经过外部认证的身份信息。
@dataclass(frozen=True)
class OperatorContext:
    operator_id: int
    role: str


# 本轮方案生成授权，绑定调查、操作员和允许使用的优惠事实。
@dataclass(frozen=True)
class ProposalContext:
    investigation_id: str
    owner_id: int
    promotion_facts: list[PromotionFact] = field(default_factory=list)


# 注入模型工具的运行依赖，包含工作区、Java 客户端和安全防线。
@dataclass
class AgentDependencies:
    pulseflow: PulseFlowApiClient
    workspace: InvestigationWorkspace
    operator_context: OperatorContext | None
    guardrail: AzurePiiGuardrail
    proposal_context: ProposalContext | None = None
    proposal_created: bool = False
    context_builder: ContextBuilder | None = None
