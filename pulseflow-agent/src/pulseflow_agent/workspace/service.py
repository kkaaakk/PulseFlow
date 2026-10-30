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


class InvestigationService:
    # 装配仓储、调查器和 PII 守卫，建立本进程准入控制器及后台任务索引。
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

    # 按 admitted 决定是否获取名额，在整体超时内运行调查，并在 finally 中按结果释放名额。
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

    # 先占用配额并检查问题及范围，再创建或恢复工作区，调度后台任务后立即返回调查；
    # 启动失败释放配额。
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

        # 向本轮调查器返回已经创建或恢复且已更新范围的工作区，不重复创建调查。
        async def factory(_question: str) -> InvestigationWorkspace:
            return workspace

        began = False

        # 运行已占用名额的后台调查；取消继续传播，其他异常只保存安全的失败诊断，
        # 不输出原始模型或守卫异常。
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

        # 移除结束任务并消费其异常；任务尚未开始就被取消时补偿释放准入名额。
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

    # 生成可安全展示的中断诊断，固定低置信度并提示恢复调查，不包含内部异常细节。
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

    # 取消本进程持有的后台任务并等待清理；仍在运行时写入 CANCELLED，其他进程拥有的运行返回冲突。
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

    # 停止接受新运行，并在十秒内尝试取消本进程全部任务；存储卡顿超时由启动恢复流程接管。
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

    # 将持久化调查快照复制为本轮可变工作区，复用仓储并保留范围、证据、假设、消息和提案。
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

    # 通过受控运行同步创建调查，待运行结束后按返回 ID 重新读取持久化结果。
    async def create(self, question: str) -> Investigation:
        # 使用经检查的问题创建持久化调查，再转换为可变工作区。
        async def factory(clean_question: str) -> InvestigationWorkspace:
            return self._workspace(await self._repository.create(clean_question))

        result = await self._run(question, factory)
        assert result.investigation_id is not None
        return await self._repository.load(result.investigation_id)

    # 检查可选新范围后，在同一调查 ID 下开始后续轮次，运行完成后重读调查快照。
    async def follow_up(
        self, investigation_id: str, question: str, scope: str | None = None
    ) -> Investigation:
        if scope is not None:
            await self._guardrail.check(scope)

        # 抢占调查后续轮次并加载历史工作区；指定新范围时先更新范围版本。
        async def factory(_clean_question: str) -> InvestigationWorkspace:
            workspace = self._workspace(await self._repository.begin_followup(investigation_id))
            if scope is not None:
                await workspace.change_scope(scope)
            return workspace

        await self._run(question, factory)
        return await self._repository.load(investigation_id)

    # 按 investigation_id 从仓储读取完整调查快照，缺失错误由仓储继续传播。
    async def get(self, investigation_id: str) -> Investigation:
        return await self._repository.load(investigation_id)

    # 先检查授权促销事实，要求已有成功诊断和当前证据，再按 owner_id 运行提案轮次；
    # 未新增提案视为冲突。
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

        # 在已经验证提案前置条件后抢占同一调查的后续轮次，保留现有证据工作区。
        async def factory(_question: str) -> InvestigationWorkspace:
            return self._workspace(await self._repository.begin_followup(investigation_id))

        await self._run(question, factory, ProposalContext(investigation_id, owner_id, facts))
        result = await self._repository.load(investigation_id)
        if len(result.proposals) == len(existing.proposals):
            raise InvestigationConflictError("proposal_not_created")
        return result
