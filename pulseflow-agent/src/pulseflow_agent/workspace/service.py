"""Creates and resumes investigation objects without exposing Agent DB internals."""

import asyncio
from collections.abc import Awaitable, Callable
from time import monotonic

from pydantic import SecretStr

from pulseflow_agent.agent.dependencies import DraftAuthorization
from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import (
    Diagnosis,
    Investigation,
    InvestigationResult,
    InvestigationWorkspace,
)
from pulseflow_agent.runtime import RunAdmission
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
        self.admission = RunAdmission(investigator._settings)
        self._tasks: dict[str, asyncio.Task[None]] = {}

    async def _run(
        self,
        question: str,
        factory: Callable[[str], Awaitable[InvestigationWorkspace]],
        authorization: DraftAuthorization | None = None,
        admitted: bool = False,
    ) -> InvestigationResult:
        if not admitted:
            await self.admission.acquire()
        started = monotonic()
        status = "FAILED"
        try:
            async with asyncio.timeout(self.admission.settings.pulseflow_agent_run_timeout_seconds):
                result = await self._investigator.run(
                    question, workspace_factory=factory, draft_authorization=authorization
                )
            status = result.diagnosis.status
            return result
        finally:
            self.admission.release(status, started)

    async def start(
        self, question: str, investigation_id: str | None = None, scope: str | None = None
    ) -> Investigation:
        await self.admission.acquire()
        try:
            await self._guardrail.check(question)
            if scope is not None:
                await self._guardrail.check(scope)
            item = (
                await self._repository.create(question)
                if investigation_id is None
                else await self._repository.begin_followup(investigation_id)
            )
            workspace = self._workspace(item)
            if scope is not None:
                await workspace.change_scope(scope)
        except BaseException:
            self.admission.release("REJECTED", monotonic())
            raise

        async def factory(_question: str) -> InvestigationWorkspace:
            return workspace

        began = False

        async def background() -> None:
            nonlocal began
            began = True
            try:
                await self._run(question, factory, admitted=True)
            except asyncio.CancelledError:
                raise
            except Exception:
                # Guardrail/model/runtime details must not enter logs or the browser.
                current = await self._repository.load(item.id)
                if current.status == "RUNNING":
                    await self._repository.finish(item.id, "FAILED", self._stopped_diagnosis())

        task = asyncio.create_task(background())
        self._tasks[item.id] = task

        def done(completed: asyncio.Task[None]) -> None:
            if not began:
                self.admission.release("CANCELLED", monotonic())
            self._tasks.pop(item.id, None)
            if not completed.cancelled():
                completed.exception()  # consume storage failures without raw exception logs

        task.add_done_callback(done)
        return item.model_copy(
            update={"scope": workspace.scope, "scope_version": workspace.scope_version}
        )

    @staticmethod
    def _stopped_diagnosis() -> Diagnosis:
        return Diagnosis(
            status="INSUFFICIENT_EVIDENCE",
            summary="Investigation interrupted.",
            findings=[],
            evidence_ids=[],
            unresolved_questions=["Please resume the investigation."],
            confidence="low",
            recommended_next_action=None,
        )

    async def cancel(self, investigation_id: str) -> Investigation:
        item = await self._repository.load(investigation_id)
        task = self._tasks.get(investigation_id)
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            item = await self._repository.load(investigation_id)
            if item.status == "RUNNING":
                await self._repository.finish(
                    investigation_id, "CANCELLED", self._stopped_diagnosis()
                )
        elif item.status == "RUNNING":
            raise InvestigationConflictError("run_not_owned_by_this_process")
        return await self._repository.load(investigation_id)

    async def shutdown(self) -> None:
        self.admission.closing = True
        try:
            async with asyncio.timeout(10):
                await asyncio.gather(
                    *(self.cancel(id) for id in list(self._tasks)), return_exceptions=True
                )
        except TimeoutError:
            # A stalled store must not prevent exit; startup recovery handles RUNNING rows.
            pass

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

        result = await self._run(question, factory)
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

        await self._run(question, factory)
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

        await self._run(question, factory, DraftAuthorization(investigation_id, grant, facts))
        result = await self._repository.load(investigation_id)
        if len(result.proposals) == len(existing.proposals):
            raise InvestigationConflictError("proposal_not_created")
        return result
