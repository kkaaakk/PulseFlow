"""Creates and resumes investigation objects without exposing Agent DB internals."""

from pydantic import SecretStr

from pulseflow_agent.agent.dependencies import DraftAuthorization
from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import Investigation, InvestigationWorkspace
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail
from pulseflow_agent.workspace.repository import InvestigationConflictError, InvestigationRepository


class InvestigationService:
    def __init__(
        self,
        repository: InvestigationRepository,
        investigator: GrowthInvestigator,
        guardrail: AzurePiiGuardrail,
    ) -> None:
        self._repository = repository
        self._investigator = investigator
        self._guardrail = guardrail

    def _workspace(self, investigation: Investigation) -> InvestigationWorkspace:
        return InvestigationWorkspace(
            goal=investigation.goal,
            id=investigation.id,
            status=investigation.status,
            scope=investigation.scope,
            scope_version=investigation.scope_version,
            repository=self._repository,
            evidence=list(investigation.evidence),
            hypotheses=list(investigation.hypotheses),
            messages=list(investigation.messages),
            tool_trajectory=list(investigation.tool_trajectory),
            proposals=list(investigation.proposals),
        )

    async def create(self, question: str) -> Investigation:
        async def factory(clean_question: str) -> InvestigationWorkspace:
            return self._workspace(await self._repository.create(clean_question))

        result = await self._investigator.run(question, workspace_factory=factory)
        assert result.investigation_id is not None
        return await self._repository.load(result.investigation_id)

    async def follow_up(
        self, investigation_id: str, question: str, scope: str | None = None
    ) -> Investigation:
        if scope is not None:
            await self._guardrail.check(scope)

        async def factory(_clean_question: str) -> InvestigationWorkspace:
            workspace = self._workspace(await self._repository.begin_followup(investigation_id))
            if scope is not None:
                await workspace.change_scope(scope)
            return workspace

        await self._investigator.run(question, workspace_factory=factory)
        return await self._repository.load(investigation_id)

    async def get(self, investigation_id: str) -> Investigation:
        return await self._repository.load(investigation_id)

    async def propose(
        self,
        investigation_id: str,
        question: str,
        grant: SecretStr,
        promotion_facts: list[PromotionFact] | None = None,
    ) -> Investigation:
        facts = list(promotion_facts or [])
        await self._guardrail.check([fact.model_dump(mode="json") for fact in facts])
        existing = await self._repository.load(investigation_id)
        if (
            existing.status != "COMPLETED"
            or existing.final_diagnosis is None
            or existing.final_diagnosis.status != "DIAGNOSED"
            or not any(item.scope_version == existing.scope_version for item in existing.evidence)
        ):
            raise InvestigationConflictError("diagnosis_required_for_proposal")

        async def factory(_question: str) -> InvestigationWorkspace:
            return self._workspace(await self._repository.begin_followup(investigation_id))

        await self._investigator.run(
            question,
            workspace_factory=factory,
            draft_authorization=DraftAuthorization(investigation_id, grant, facts),
        )
        result = await self._repository.load(investigation_id)
        if len(result.proposals) == len(existing.proposals):
            raise InvestigationConflictError("proposal_not_created")
        return result
