"""One Pydantic AI agent that chooses Java read-only tools dynamically."""

from asyncio import CancelledError, shield
from collections.abc import Awaitable, Callable
from dataclasses import replace
from typing import Literal

import httpx
from opentelemetry import trace
from pydantic_ai import Agent, ModelResponse, ModelRetry, RunContext, ToolDefinition
from pydantic_ai.exceptions import UsageLimitExceeded
from pydantic_ai.models import Model
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.models.test import TestModel
from pydantic_ai.profiles.openai import OpenAIModelProfile
from pydantic_ai.providers.deepseek import DeepSeekProvider
from pydantic_ai.usage import RunUsage, UsageLimits

from pulseflow_agent.agent.dependencies import (
    AgentDependencies,
    OperatorContext,
    ProposalContext,
)
from pulseflow_agent.agent.instructions import GROWTH_INVESTIGATOR_INSTRUCTIONS
from pulseflow_agent.agent.observations import (
    attribution_observation,
    audience_observation,
    compare_observation,
    performance_observation,
    query_observation,
)
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient, ToolClientError
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.campaign_proposal import CampaignProposal
from pulseflow_agent.domain.contracts import (
    AttributionArgs,
    BreakdownMetricArgs,
    CampaignPerformanceArgs,
    CompareMetricArgs,
    PerformanceResponse,
    PreviewAudienceArgs,
    QueryMetricArgs,
    ToolResponse,
    WireModel,
)
from pulseflow_agent.domain.investigation import (
    Diagnosis,
    Hypothesis,
    HypothesisStatus,
    InvestigationResult,
    InvestigationWorkspace,
    ToolObservation,
)
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.workspace.context import (
    ContextBudgetExceeded,
    ContextBuilder,
    context_token_budget,
)


# 创建显式离线测试模型或 DeepSeek 模型，复用受管理的 HTTP 客户端。
def create_model(
    settings: AgentSettings, http_client: httpx.AsyncClient | None = None
) -> TestModel | OpenAIChatModel:
    if settings.is_test_model:
        # Offline startup is honest about having gathered no business evidence.
        # Scenario tests override this with FunctionModel to exercise tool choice.
        return TestModel(
            call_tools=[],
            custom_output_args={
                "status": "INSUFFICIENT_EVIDENCE",
                "summary": "Offline test model did not investigate business data.",
                "findings": [],
                "evidence_ids": [],
                "unresolved_questions": [
                    "A real model and Java data are needed for investigation."
                ],
                "confidence": "low",
                "recommended_next_action": None,
            },
        )
    api_key = settings.pulseflow_agent_api_key
    assert api_key is not None  # validated by AgentSettings
    provider = DeepSeekProvider(api_key=api_key.get_secret_value(), http_client=http_client)
    if settings.pulseflow_agent_base_url:
        provider.client.base_url = str(settings.pulseflow_agent_base_url)
    provider.client.timeout = settings.pulseflow_agent_run_timeout_seconds
    # Wire retries are otherwise invisible to PydanticAI UsageLimits.
    provider.client.max_retries = 0
    model_name = settings.pulseflow_agent_model.removeprefix("deepseek:")
    profile = provider.model_profile(model_name)
    assert isinstance(profile, OpenAIModelProfile)
    # Thinking mode rejects required/forced tool choice. Preserve DeepSeek's
    # reasoning_content round trips while allowing the model to choose a tool.
    profile = replace(profile, openai_supports_tool_choice_required=False)
    return OpenAIChatModel(model_name, provider=provider, profile=profile)


# 把请求次数、工具调用次数及输入输出 token 上限转换为本次运行的预算。
def create_usage_limits(settings: AgentSettings) -> UsageLimits:
    return UsageLimits(
        request_limit=settings.pulseflow_agent_max_model_requests,
        tool_calls_limit=settings.pulseflow_agent_max_tool_calls,
        input_tokens_limit=settings.pulseflow_agent_max_input_tokens,
        output_tokens_limit=settings.pulseflow_agent_max_output_tokens,
    )


# 自主选择聚合工具的调查 Agent，返回经过证据及 PII 校验的诊断。
class GrowthInvestigator:
    # 注入业务客户端、PII 防线和遥测组件，构建返回 Diagnosis 的 Agent 并注册工具。
    def __init__(
        self,
        settings: AgentSettings,
        guardrail: AzurePiiGuardrail,
        pulseflow: PulseFlowApiClient,
        model: Model | None = None,
        telemetry: Telemetry | None = None,
    ) -> None:
        self._settings = settings
        self._guardrail = guardrail
        self._pulseflow = pulseflow
        self.telemetry = telemetry or Telemetry()
        self._agent: Agent[AgentDependencies, Diagnosis] = Agent(
            model or create_model(settings),
            deps_type=AgentDependencies,
            output_type=Diagnosis,
            instructions=GROWTH_INVESTIGATOR_INSTRUCTIONS,
            retries={"output": 1},
            name="growth_investigator",
            capabilities=[self.telemetry.agent_instrumentation()],
        )
        self._register_tools()

    # 检查 Java 聚合观察文本后记录带数据版本的证据，返回模型可引用的证据 ID 和摘要。
    async def _save(
        self,
        ctx: RunContext[AgentDependencies],
        name: str,
        args: WireModel,
        response: ToolResponse,
        observation: str,
    ) -> ToolObservation:
        metadata = response.metadata
        # Only this compact, Java-derived observation goes back to the LLM.
        await ctx.deps.guardrail.check(
            {
                "observation": observation,
                "source": metadata.source,
                "dataVersion": metadata.data_version,
                "warnings": metadata.warnings,
            }
        )
        evidence = await ctx.deps.workspace.add_evidence(name, metadata, observation, args)
        trace.get_current_span().set_attributes(
            {
                "evidence.id": evidence.id,
                "tool.name": name,
                "investigation.id": ctx.deps.workspace.id or "ephemeral",
            }
        )
        return ToolObservation(
            evidence_id=evidence.id, observation=observation, warnings=list(metadata.warnings)
        )

    # 注册只读查询、假设管理和方案持久化工具，以及动态上下文与最终诊断校验器。
    def _register_tools(self) -> None:
        agent = self._agent

        # 把已有证据、假设和调查范围注入本轮提示，并在发给模型前执行 PII 检查。
        @agent.instructions
        async def investigation_context(ctx: RunContext[AgentDependencies]) -> str:
            builder = ctx.deps.context_builder
            assert builder is not None
            suffix = ""
            if ctx.deps.proposal_context is not None:
                suffix = "\nOperator-authorized promotion facts (do not add or replace): " + str(
                    [
                        item.model_dump(mode="json", by_alias=True)
                        for item in ctx.deps.proposal_context.promotion_facts
                    ]
                )
            context = (
                builder.build(
                    scope=ctx.deps.workspace.scope,
                    scope_version=ctx.deps.workspace.scope_version,
                    token_budget=context_token_budget(
                        self._settings,
                        input_tokens_used=ctx.usage.input_tokens,
                        output_tokens_used=ctx.usage.output_tokens,
                        fixed_text=GROWTH_INVESTIGATOR_INSTRUCTIONS + builder.question + suffix,
                    ),
                )
                + suffix
            )
            await ctx.deps.guardrail.check(context)
            return context

        # 记录指标查询轨迹，调用 Java 聚合接口并将结果转换为可引用的调查证据。
        @agent.tool
        async def query_metric(
            ctx: RunContext[AgentDependencies], request: QueryMetricArgs
        ) -> ToolObservation:
            """Query one authoritative campaign metric over a bounded period."""
            await ctx.deps.workspace.record_tool("query_metric")
            try:
                response = await ctx.deps.pulseflow.query_metric(request)
                return await self._save(
                    ctx, "query_metric", request, response, query_observation(response)
                )
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        # 查询当前期与基准期的差异，保留 Java 返回的口径和警告作为证据。
        @agent.tool
        async def compare_metric(
            ctx: RunContext[AgentDependencies], request: CompareMetricArgs
        ) -> ToolObservation:
            """Compare one metric across two periods; Java computes both deltas."""
            await ctx.deps.workspace.record_tool("compare_metric")
            try:
                response = await ctx.deps.pulseflow.compare_metric(request)
                return await self._save(
                    ctx, "compare_metric", request, response, compare_observation(response)
                )
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        # 按白名单维度拆解指标，将分组聚合结果保存为证据。
        @agent.tool
        async def breakdown_metric(
            ctx: RunContext[AgentDependencies], request: BreakdownMetricArgs
        ) -> ToolObservation:
            """Break one metric down by campaign, channel, or day."""
            await ctx.deps.workspace.record_tool("breakdown_metric")
            try:
                response = await ctx.deps.pulseflow.breakdown_metric(request)
                return await self._save(
                    ctx, "breakdown_metric", request, response, query_observation(response)
                )
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        # 读取权威活动绩效，清理返回内容后生成摘要证据；失败返回安全错误码。
        @agent.tool
        async def get_campaign_performance(
            ctx: RunContext[AgentDependencies], campaign_id: int
        ) -> ToolObservation:
            """Read a precomputed campaign summary, without triggering a write."""
            await ctx.deps.workspace.record_tool("get_campaign_performance")
            try:
                response: PerformanceResponse = await ctx.deps.pulseflow.get_campaign_performance(
                    campaign_id
                )
                query = CampaignPerformanceArgs(campaign_id=campaign_id)
                evidence = await self._save(
                    ctx,
                    "get_campaign_performance",
                    query,
                    response,
                    performance_observation(response),
                )
                return evidence
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        # 获取聚合归因结果，保存查询条件、来源和摘要供推理引用。
        @agent.tool
        async def get_attribution_breakdown(
            ctx: RunContext[AgentDependencies], request: AttributionArgs
        ) -> ToolObservation:
            """Get aggregate attributed conversions by a supported dimension."""
            await ctx.deps.workspace.record_tool("get_attribution_breakdown")
            try:
                response = await ctx.deps.pulseflow.get_attribution_breakdown(request)
                return await self._save(
                    ctx,
                    "get_attribution_breakdown",
                    request,
                    response,
                    attribution_observation(response),
                )
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        # 调用 Java 校验并预估 DSL 人群，将人数与警告记录为证据。
        @agent.tool
        async def preview_audience(
            ctx: RunContext[AgentDependencies], request: PreviewAudienceArgs
        ) -> ToolObservation:
            """Validate a Campaign DSL and preview only its aggregate audience size."""
            await ctx.deps.workspace.record_tool("preview_audience")
            try:
                response = await ctx.deps.pulseflow.preview_audience(request)
                return await self._save(
                    ctx, "preview_audience", request, response, audience_observation(response)
                )
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        # 检查假设文本和证据引用，创建当前范围内的待验证假设；非法引用要求模型重试。
        @agent.tool
        async def propose_hypothesis(
            ctx: RunContext[AgentDependencies],
            statement: str,
            supporting_evidence_ids: list[str],
            contradicting_evidence_ids: list[str],
            reason: str | None = None,
        ) -> Hypothesis:
            """Add a tentative hypothesis linked only to existing business Evidence."""
            await ctx.deps.workspace.record_tool("propose_hypothesis")
            await ctx.deps.guardrail.check([statement, reason])
            try:
                return await ctx.deps.workspace.propose_hypothesis(
                    statement, supporting_evidence_ids, contradicting_evidence_ids, reason
                )
            except ValueError:
                raise ModelRetry("Hypothesis evidence must exist in the current scope.") from None

        # 依据支持及反对证据更新当前范围的假设状态、理由和置信度。
        @agent.tool
        async def update_hypothesis(
            ctx: RunContext[AgentDependencies],
            hypothesis_id: str,
            status: HypothesisStatus,
            supporting_evidence_ids: list[str],
            contradicting_evidence_ids: list[str],
            reason: str,
            confidence: Literal["low", "medium", "high"] | None = None,
        ) -> Hypothesis:
            """Strengthen, weaken or reject a hypothesis using current Evidence."""
            await ctx.deps.workspace.record_tool("update_hypothesis")
            await ctx.deps.guardrail.check(reason)
            try:
                updated = await ctx.deps.workspace.update_hypothesis(
                    hypothesis_id,
                    status,
                    supporting_evidence_ids,
                    contradicting_evidence_ids,
                    reason,
                    confidence,
                )
                await ctx.deps.guardrail.check([updated.statement, updated.reason])
                return updated
            except ValueError:
                raise ModelRetry("Hypothesis or Evidence is not in the current scope.") from None

        # 检查假设文本后返回工作区假设，帮助模型复用已有推理进度。
        @agent.tool
        async def list_hypotheses(ctx: RunContext[AgentDependencies]) -> list[Hypothesis]:
            """List hypotheses already recorded in this Investigation Workspace."""
            await ctx.deps.workspace.record_tool("list_hypotheses")
            await ctx.deps.guardrail.check(
                [
                    text
                    for item in ctx.deps.workspace.hypotheses
                    for text in (item.statement, item.reason)
                    if text is not None
                ]
            )
            return ctx.deps.workspace.hypotheses

        # 应用用户请求的范围变更；旧范围证据降为背景，不能直接证明新范围结论。
        @agent.tool
        async def update_scope(ctx: RunContext[AgentDependencies], description: str) -> str:
            """Apply a user-requested investigation scope change within the same ID."""
            await ctx.deps.workspace.record_tool("update_scope")
            await ctx.deps.guardrail.check(description)
            try:
                version = await ctx.deps.workspace.change_scope(description)
            except ValueError:
                raise ModelRetry("Scope must be a nonempty user-requested description.") from None
            return f"scopeVersion={version}; previous scoped evidence is historical background"

        # 授权归属匹配、有当前证据且本轮尚未生成方案时，才向模型开放方案工具。
        async def prepare_proposal(
            ctx: RunContext[AgentDependencies], definition: ToolDefinition
        ) -> ToolDefinition | None:
            authorization = ctx.deps.proposal_context
            if (
                authorization is None
                or ctx.deps.proposal_created
                or authorization.investigation_id != ctx.deps.workspace.id
                or not ctx.deps.workspace.evidence_ids
            ):
                return None
            return definition

        # 核对证据和操作员授权的优惠事实，持久化本轮唯一方案并返回方案 ID。
        @agent.tool(prepare=prepare_proposal, sequential=True)
        async def persist_campaign_proposal(
            ctx: RunContext[AgentDependencies], proposal: CampaignProposal
        ) -> str:
            """Persist one evidence-backed proposal; Java handles draft creation later."""
            authorization = ctx.deps.proposal_context
            if (
                authorization is None
                or ctx.deps.proposal_created
                or authorization.investigation_id != ctx.deps.workspace.id
            ):
                raise ModelRetry("This run cannot persist a proposal.")
            if not set(proposal.supporting_evidence_ids).issubset(ctx.deps.workspace.evidence_ids):
                raise ModelRetry("Proposal evidence must exist in the current investigation scope.")
            if proposal.promotion_facts != authorization.promotion_facts:
                raise ModelRetry("Use only the exact operator-authorized promotion facts, or none.")
            await ctx.deps.guardrail.check(proposal.free_text())
            record = await ctx.deps.workspace.add_proposal(proposal, authorization.owner_id)
            ctx.deps.proposal_created = True
            return f"proposalId={record.id}; status=GENERATED"

        # 验证结论只引用当前范围的真实证据；证据不足时要求低置信度和未解决问题。
        @agent.output_validator
        def validate_diagnosis(ctx: RunContext[AgentDependencies], output: Diagnosis) -> Diagnosis:
            known = ctx.deps.workspace.evidence_ids
            references = set(output.evidence_ids)
            for finding in output.findings:
                references.update(finding.evidence_ids)
                if not set(finding.evidence_ids).issubset(set(output.evidence_ids)):
                    self.telemetry.metrics.reference_check(False)
                    raise ModelRetry("Finding evidence must also be listed in evidence_ids.")
            if not references.issubset(known):
                self.telemetry.metrics.reference_check(False)
                raise ModelRetry("Cite only evidence IDs returned by tools in this investigation.")
            if output.status == "DIAGNOSED" and not references:
                self.telemetry.metrics.reference_check(False)
                raise ModelRetry("A diagnosis requires business evidence.")
            if output.status == "INSUFFICIENT_EVIDENCE" and (
                output.confidence != "low" or not output.unresolved_questions
            ):
                raise ModelRetry(
                    "Insufficient evidence requires low confidence and open questions."
                )
            self.telemetry.metrics.reference_check(True)
            return output

    # 检查输入后执行受预算约束的调查，持久化终态与诊断；取消和异常记录后继续抛出。
    # 最终摘要也须通过 PII 检查，避免被拦截的输出进入业务结论。
    async def run(
        self,
        prompt: str,
        operator_context: OperatorContext | None = None,
        workspace_factory: Callable[[str], Awaitable[InvestigationWorkspace]] | None = None,
        proposal_context: ProposalContext | None = None,
    ) -> InvestigationResult:
        try:
            await self._guardrail.check(prompt)
        except PiiBlockedError:
            self.telemetry.metrics.record_status("PII_BLOCKED")
            raise
        workspace = (
            await workspace_factory(prompt)
            if workspace_factory is not None
            else InvestigationWorkspace(goal=prompt)
        )
        context_builder = ContextBuilder(workspace, prompt)
        await workspace.add_message("USER", prompt)
        deps = AgentDependencies(
            self._pulseflow,
            workspace,
            operator_context,
            self._guardrail,
            proposal_context=proposal_context,
            context_builder=context_builder,
        )
        usage = RunUsage()
        cost: float | None = None
        with trace.get_tracer(
            __name__, tracer_provider=self.telemetry.provider
        ).start_as_current_span(
            "agent.investigation", record_exception=False, set_status_on_exception=False
        ) as span:
            span.set_attribute(
                "agent.model_provider", "test" if self._settings.is_test_model else "deepseek"
            )
            span.set_attribute("investigation.id", workspace.id or "ephemeral")
            try:
                result = await self._agent.run(
                    prompt,
                    deps=deps,
                    usage=usage,
                    usage_limits=create_usage_limits(self._settings),
                    model_settings={"timeout": 20.0, "max_tokens": 2000},
                )
                diagnosis = result.output
                try:
                    costs = [
                        message.cost().total_price
                        for message in result.new_messages()
                        if isinstance(message, ModelResponse)
                    ]
                    cost = float(sum(costs)) if costs else None
                except Exception:
                    cost = None  # unknown provider pricing is not zero cost
                status: Literal["COMPLETED", "INSUFFICIENT_EVIDENCE", "BUDGET_EXHAUSTED"] = (
                    "COMPLETED" if diagnosis.status == "DIAGNOSED" else "INSUFFICIENT_EVIDENCE"
                )
            except UsageLimitExceeded as error:
                context_exceeded = isinstance(error, ContextBudgetExceeded)
                diagnosis = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE",
                    summary=(
                        "Required investigation context exceeds its text budget."
                        if context_exceeded
                        else "Investigation stopped at its usage budget."
                    ),
                    findings=[],
                    evidence_ids=sorted(workspace.evidence_ids),
                    unresolved_questions=[
                        "Narrow the investigation scope or review its protected evidence."
                        if context_exceeded
                        else "Which additional business facts would resolve the question?"
                    ],
                    confidence="low",
                    recommended_next_action=None,
                )
                status = "BUDGET_EXHAUSTED"
            except CancelledError:
                cancelled = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE",
                    summary="Investigation cancelled.",
                    findings=[],
                    evidence_ids=[],
                    unresolved_questions=["The investigation was cancelled before completion."],
                    confidence="low",
                    recommended_next_action=None,
                )
                await shield(workspace.finish("CANCELLED", cancelled))
                self.telemetry.metrics.record_status("CANCELLED", usage)
                raise
            except Exception as error:
                failure = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE",
                    summary="Investigation failed.",
                    findings=[],
                    evidence_ids=[],
                    unresolved_questions=["The investigation did not complete."],
                    confidence="low",
                    recommended_next_action=None,
                )
                await workspace.finish("FAILED", failure)
                self.telemetry.metrics.record_status(
                    "PII_BLOCKED" if isinstance(error, PiiBlockedError) else "FAILED", usage
                )
                raise
            try:
                await self._guardrail.check(
                    {
                        "summary": diagnosis.summary,
                        "findings": [finding.claim for finding in diagnosis.findings],
                        "unresolved": diagnosis.unresolved_questions,
                        "recommendedAction": diagnosis.recommended_next_action,
                    }
                )
            except Exception as error:
                failure = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE",
                    summary="Final output blocked.",
                    findings=[],
                    evidence_ids=[],
                    unresolved_questions=["The final output did not pass safety checks."],
                    confidence="low",
                    recommended_next_action=None,
                )
                await workspace.finish("FAILED", failure)
                self.telemetry.metrics.record_status(
                    "PII_BLOCKED" if isinstance(error, PiiBlockedError) else "FAILED", usage
                )
                raise
            await workspace.finish(status, diagnosis)
            self.telemetry.metrics.record_status(status, usage, cost)
            span.set_attributes(
                {"agent.request_count": usage.requests, "agent.tool_call_count": usage.tool_calls}
            )
            return InvestigationResult(
                diagnosis=diagnosis,
                evidence=workspace.evidence,
                tool_trajectory=workspace.tool_trajectory,
                investigation_id=workspace.id,
                hypotheses=workspace.hypotheses,
                proposals=workspace.proposals,
            )
