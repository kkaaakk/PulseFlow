"""Creates and resumes investigation objects without exposing Agent DB internals."""

import asyncio
from collections.abc import Awaitable, Callable
from time import monotonic

from pulseflow_agent.agent.dependencies import ProposalContext
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


# 协调调查持久化、后台运行、追问、取消和方案授权。
class InvestigationService:
    # 组合存储、Agent 和 PII 防线，初始化容量控制和本进程任务索引。
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

    # 在容量与超时限制内调查，无论成功、取消或失败都在 finally 中归还名额。
    async def _run(
        self,
        question: str,
        factory: Callable[[str], Awaitable[InvestigationWorkspace]],
        authorization: ProposalContext | None = None,
        admitted: bool = False,
    ) -> InvestigationResult:
        if not admitted:
            await self.admission.acquire()
        started = monotonic()
        status = "FAILED"
        try:
            async with asyncio.timeout(self.admission.settings.pulseflow_agent_run_timeout_seconds):
                result = await self._investigator.run(
                    question, workspace_factory=factory, proposal_context=authorization
                )
            status = result.diagnosis.status
            return result
        finally:
            self.admission.release(status, started)

    # 占用名额并检查问题和范围，再创建或恢复工作区，后台执行并立即返回快照。
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

        # 返回已检查和设置范围的工作区，避免后台执行时重复创建调查。
        async def factory(_question: str) -> InvestigationWorkspace:
            return workspace

        began = False

        # 执行已获准的后台调查，失败时把仍运行的记录收敛为安全的 FAILED 状态。
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

        # 移除结束任务；尚未启动即取消时归还名额，消费异常以避免输出原始错误。
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

    # 生成中断时的低置信度诊断，明确需要恢复调查。
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

    # 取消并等待本进程任务；记录仍运行但不属于本进程时返回冲突。
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

    # 停止接纳新调查，限时取消任务；存储阻塞时由下次启动恢复状态。
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

    # 复制持久化调查的可变列表和范围状态，构造本轮工作区。
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

    # 同步执行新调查，结束后按 ID 读取最终持久化结果。
    async def create(self, question: str) -> Investigation:
        # 用通过 PII 检查的问题建记录，并转为带存储能力的工作区。
        async def factory(clean_question: str) -> InvestigationWorkspace:
            return self._workspace(await self._repository.create(clean_question))

        result = await self._run(question, factory)
        assert result.investigation_id is not None
        return await self._repository.load(result.investigation_id)

    # 检查可选范围后恢复调查并追问，沿用原 ID 的证据和会话历史。
    async def follow_up(
        self, investigation_id: str, question: str, scope: str | None = None
    ) -> Investigation:
        if scope is not None:
            await self._guardrail.check(scope)

        # 切换到 RUNNING，按请求更新范围，返回追问工作区。
        async def factory(_clean_question: str) -> InvestigationWorkspace:
            workspace = self._workspace(await self._repository.begin_followup(investigation_id))
            if scope is not None:
                await workspace.change_scope(scope)
            return workspace

        await self._run(question, factory)
        return await self._repository.load(investigation_id)

    # 按 ID 返回持久化快照，供查询接口和事件流读取进度。
    async def get(self, investigation_id: str) -> Investigation:
        return await self._repository.load(investigation_id)

    # 要求完成诊断和当前证据，检查授权优惠事实后执行允许生成方案的一轮调查。
    async def propose(
        self,
        investigation_id: str,
        question: str,
        owner_id: int,
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

        # 恢复调查为 RUNNING，供带操作员授权的 Agent 继续执行。
        async def factory(_question: str) -> InvestigationWorkspace:
            return self._workspace(await self._repository.begin_followup(investigation_id))

        await self._run(question, factory, ProposalContext(investigation_id, owner_id, facts))
        result = await self._repository.load(investigation_id)
        if len(result.proposals) == len(existing.proposals):
            raise InvestigationConflictError("proposal_not_created")
        return result
