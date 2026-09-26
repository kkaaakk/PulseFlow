"""One Pydantic AI agent that chooses Java read-only tools dynamically."""

from asyncio import CancelledError, shield
from collections.abc import Awaitable, Callable
from typing import Literal

from opentelemetry import trace
from pydantic_ai import Agent, ModelRetry, RunContext
from pydantic_ai.exceptions import UsageLimitExceeded
from pydantic_ai.models import Model
from pydantic_ai.models.openai import OpenAIModel
from pydantic_ai.models.test import TestModel
from pydantic_ai.providers.openai import OpenAIProvider
from pydantic_ai.usage import UsageLimits

from pulseflow_agent.agent.dependencies import AgentDependencies, OperatorContext
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
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail


def create_model(settings: AgentSettings) -> TestModel | OpenAIModel:
    if settings.is_test_model:
        # Offline startup is honest about having gathered no business evidence.
        # Scenario tests override this with FunctionModel to exercise tool choice.
        return TestModel(call_tools=[], custom_output_args={
            "status": "INSUFFICIENT_EVIDENCE",
            "summary": "Offline test model did not investigate business data.",
            "findings": [],
            "evidence_ids": [],
            "unresolved_questions": ["A real model and Java data are needed for investigation."],
            "confidence": "low",
            "recommended_next_action": None,
        })
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
    def __init__(
        self,
        settings: AgentSettings,
        guardrail: AzurePiiGuardrail,
        pulseflow: PulseFlowApiClient,
        model: Model | None = None,
    ) -> None:
        self._settings = settings
        self._guardrail = guardrail
        self._pulseflow = pulseflow
        self._agent: Agent[AgentDependencies, Diagnosis] = Agent(
            model or create_model(settings),
            deps_type=AgentDependencies,
            output_type=Diagnosis,
            instructions=GROWTH_INVESTIGATOR_INSTRUCTIONS,
            retries={"output": 1},
        )
        self._register_tools()

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
        await ctx.deps.guardrail.check({
            "observation": observation,
            "source": metadata.source,
            "dataVersion": metadata.data_version,
            "warnings": metadata.warnings,
        })
        evidence = await ctx.deps.workspace.add_evidence(name, metadata, observation, args)
        return ToolObservation(
            evidence_id=evidence.id, observation=observation, warnings=list(metadata.warnings)
        )

    def _register_tools(self) -> None:
        agent = self._agent

        @agent.instructions
        async def investigation_context(ctx: RunContext[AgentDependencies]) -> str:
            context = ctx.deps.workspace.context_text()
            await ctx.deps.guardrail.check(context)
            return context

        @agent.tool
        async def query_metric(
            ctx: RunContext[AgentDependencies], request: QueryMetricArgs
        ) -> ToolObservation:
            """Query one authoritative campaign metric over a bounded period."""
            await ctx.deps.workspace.record_tool("query_metric")
            try:
                response = await ctx.deps.pulseflow.query_metric(request)
                return await self._save(ctx, "query_metric", request, response,
                                        query_observation(response))
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        @agent.tool
        async def compare_metric(
            ctx: RunContext[AgentDependencies], request: CompareMetricArgs
        ) -> ToolObservation:
            """Compare one metric across two periods; Java computes both deltas."""
            await ctx.deps.workspace.record_tool("compare_metric")
            try:
                response = await ctx.deps.pulseflow.compare_metric(request)
                return await self._save(ctx, "compare_metric", request, response,
                                        compare_observation(response))
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        @agent.tool
        async def breakdown_metric(
            ctx: RunContext[AgentDependencies], request: BreakdownMetricArgs
        ) -> ToolObservation:
            """Break one metric down by campaign, channel, or day."""
            await ctx.deps.workspace.record_tool("breakdown_metric")
            try:
                response = await ctx.deps.pulseflow.breakdown_metric(request)
                return await self._save(ctx, "breakdown_metric", request, response,
                                        query_observation(response))
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

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
                evidence = await self._save(ctx, "get_campaign_performance", query, response,
                                            performance_observation(response))
                return evidence
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        @agent.tool
        async def get_attribution_breakdown(
            ctx: RunContext[AgentDependencies], request: AttributionArgs
        ) -> ToolObservation:
            """Get aggregate attributed conversions by a supported dimension."""
            await ctx.deps.workspace.record_tool("get_attribution_breakdown")
            try:
                response = await ctx.deps.pulseflow.get_attribution_breakdown(request)
                return await self._save(ctx, "get_attribution_breakdown", request, response,
                                        attribution_observation(response))
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        @agent.tool
        async def preview_audience(
            ctx: RunContext[AgentDependencies], request: PreviewAudienceArgs
        ) -> ToolObservation:
            """Validate a Campaign DSL and preview only its aggregate audience size."""
            await ctx.deps.workspace.record_tool("preview_audience")
            try:
                response = await ctx.deps.pulseflow.preview_audience(request)
                return await self._save(ctx, "preview_audience", request, response,
                                        audience_observation(response))
            except ToolClientError as error:
                return ToolObservation(evidence_id=None, observation=error.code)

        @agent.tool
        async def propose_hypothesis(
            ctx: RunContext[AgentDependencies], statement: str,
            supporting_evidence_ids: list[str], contradicting_evidence_ids: list[str],
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

        @agent.tool
        async def update_hypothesis(
            ctx: RunContext[AgentDependencies], hypothesis_id: str, status: HypothesisStatus,
            supporting_evidence_ids: list[str], contradicting_evidence_ids: list[str],
            reason: str, confidence: Literal["low", "medium", "high"] | None = None,
        ) -> Hypothesis:
            """Strengthen, weaken or reject a hypothesis using current Evidence."""
            await ctx.deps.workspace.record_tool("update_hypothesis")
            await ctx.deps.guardrail.check(reason)
            try:
                updated = await ctx.deps.workspace.update_hypothesis(
                    hypothesis_id, status, supporting_evidence_ids,
                    contradicting_evidence_ids, reason, confidence,
                )
                await ctx.deps.guardrail.check([updated.statement, updated.reason])
                return updated
            except ValueError:
                raise ModelRetry("Hypothesis or Evidence is not in the current scope.") from None

        @agent.tool
        async def list_hypotheses(ctx: RunContext[AgentDependencies]) -> list[Hypothesis]:
            """List hypotheses already recorded in this Investigation Workspace."""
            await ctx.deps.workspace.record_tool("list_hypotheses")
            await ctx.deps.guardrail.check([
                text for item in ctx.deps.workspace.hypotheses
                for text in (item.statement, item.reason) if text is not None
            ])
            return ctx.deps.workspace.hypotheses

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

        @agent.output_validator
        def validate_diagnosis(
            ctx: RunContext[AgentDependencies], output: Diagnosis
        ) -> Diagnosis:
            known = ctx.deps.workspace.evidence_ids
            references = set(output.evidence_ids)
            for finding in output.findings:
                references.update(finding.evidence_ids)
                if not set(finding.evidence_ids).issubset(set(output.evidence_ids)):
                    raise ModelRetry("Finding evidence must also be listed in evidence_ids.")
            if not references.issubset(known):
                raise ModelRetry("Cite only evidence IDs returned by tools in this investigation.")
            if output.status == "DIAGNOSED" and not references:
                raise ModelRetry("A diagnosis requires business evidence.")
            if output.status == "INSUFFICIENT_EVIDENCE" and (
                output.confidence != "low" or not output.unresolved_questions
            ):
                raise ModelRetry(
                    "Insufficient evidence requires low confidence and open questions."
                )
            return output

    async def run(
        self, prompt: str, operator_context: OperatorContext | None = None,
        workspace_factory: Callable[[str], Awaitable[InvestigationWorkspace]] | None = None,
    ) -> InvestigationResult:
        await self._guardrail.check(prompt)
        workspace = (
            await workspace_factory(prompt)
            if workspace_factory is not None else InvestigationWorkspace(goal=prompt)
        )
        await workspace.add_message("USER", prompt)
        deps = AgentDependencies(self._pulseflow, workspace, operator_context, self._guardrail)
        with trace.get_tracer(__name__).start_as_current_span(
            "agent.investigation", record_exception=False, set_status_on_exception=False
        ) as span:
            span.set_attribute(
                "agent.model_provider", "test" if self._settings.is_test_model else "openai"
            )
            try:
                result = await self._agent.run(
                    prompt, deps=deps, usage_limits=create_usage_limits(self._settings)
                )
                diagnosis = result.output
                status: Literal["COMPLETED", "INSUFFICIENT_EVIDENCE", "BUDGET_EXHAUSTED"] = (
                    "COMPLETED" if diagnosis.status == "DIAGNOSED" else "INSUFFICIENT_EVIDENCE"
                )
            except UsageLimitExceeded:
                diagnosis = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE",
                    summary="Investigation stopped at its usage budget.",
                    findings=[],
                    evidence_ids=[item.id for item in workspace.evidence],
                    unresolved_questions=[
                        "Which additional business facts would resolve the question?"
                    ],
                    confidence="low",
                    recommended_next_action=None,
                )
                status = "BUDGET_EXHAUSTED"
            except CancelledError:
                cancelled = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE", summary="Investigation cancelled.",
                    findings=[], evidence_ids=[],
                    unresolved_questions=["The investigation was cancelled before completion."],
                    confidence="low", recommended_next_action=None,
                )
                await shield(workspace.finish("CANCELLED", cancelled))
                raise
            except Exception:
                failure = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE", summary="Investigation failed.",
                    findings=[], evidence_ids=[],
                    unresolved_questions=["The investigation did not complete."],
                    confidence="low", recommended_next_action=None,
                )
                await workspace.finish("FAILED", failure)
                raise
            try:
                await self._guardrail.check({
                    "summary": diagnosis.summary,
                    "findings": [finding.claim for finding in diagnosis.findings],
                    "unresolved": diagnosis.unresolved_questions,
                    "recommendedAction": diagnosis.recommended_next_action,
                })
            except Exception:
                failure = Diagnosis(
                    status="INSUFFICIENT_EVIDENCE", summary="Final output blocked.",
                    findings=[], evidence_ids=[],
                    unresolved_questions=["The final output did not pass safety checks."],
                    confidence="low", recommended_next_action=None,
                )
                await workspace.finish("FAILED", failure)
                raise
            await workspace.finish(status, diagnosis)
            return InvestigationResult(
                diagnosis=diagnosis,
                evidence=workspace.evidence,
                tool_trajectory=workspace.tool_trajectory,
                investigation_id=workspace.id,
            )
