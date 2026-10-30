"""FastAPI application with readiness checked at startup."""

import asyncio
import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from hmac import compare_digest
from time import monotonic
from typing import Annotated

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from sqlalchemy.ext.asyncio import create_async_engine
from starlette.middleware.base import RequestResponseEndpoint
from starlette.responses import JSONResponse, Response, StreamingResponse

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator
from pulseflow_agent.clients.pulseflow_api import PulseFlowApiClient
from pulseflow_agent.config import AgentSettings
from pulseflow_agent.domain.campaign_proposal import ProposalRead, ProposalRecord
from pulseflow_agent.domain.contracts import PromotionFact
from pulseflow_agent.domain.investigation import Investigation
from pulseflow_agent.observability.tracing import Telemetry
from pulseflow_agent.runtime import AdmissionRejected
from pulseflow_agent.security.pii_guardrail import AzurePiiGuardrail, PiiBlockedError
from pulseflow_agent.workspace.repository import (
    InvestigationConflictError,
    InvestigationNotFoundError,
    SqlAlchemyInvestigationRepository,
)
from pulseflow_agent.workspace.service import InvestigationService


class InvestigationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
    ]


class FollowUpRequest(InvestigationRequest):
    scope: Annotated[
        str | None, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ] = None


class ProposalRequest(InvestigationRequest):
    owner_id: int = Field(gt=0)
    promotion_facts: list[PromotionFact] = Field(default_factory=list, max_length=10)


class DraftCreatedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    draft_id: int = Field(gt=0)


# 构造带内部令牌鉴权、健康检查、调查及提案接口的 FastAPI 应用；资源初始化和释放由 lifespan 管理
# 。
def create_app(
    settings: AgentSettings | None = None, trace_runtime: Telemetry | None = None
) -> FastAPI:
    telemetry = trace_runtime or Telemetry()

    # 校验配置、建立数据库与 HTTP 客户端、恢复中断调查并设置就绪；
    # 退出时停止任务并释放连接及遥测。
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        configured = settings or AgentSettings()  # type: ignore[call-arg]
        if (
            configured.pulseflow_agent_internal_token is None
            or not configured.pulseflow_agent_internal_token.get_secret_value()
        ):
            raise ValueError("Java internal token is required for investigation tools")
        database_url = configured.pulseflow_agent_database_url
        assert database_url is not None  # AgentSettings requires it
        try:
            pool_options: dict[str, int] = {}
            if database_url.get_secret_value().startswith("mysql+"):
                pool_options = {
                    "pool_size": configured.pulseflow_agent_db_pool_size,
                    "max_overflow": 0,
                    "pool_timeout": 5,
                }
            engine = create_async_engine(
                database_url.get_secret_value(), pool_pre_ping=True, **pool_options
            )
        except Exception:
            raise ValueError("invalid Agent database configuration") from None
        app.state.settings = configured
        if configured.pulseflow_agent_otel_endpoint:
            telemetry.configure_endpoint(str(configured.pulseflow_agent_otel_endpoint))
        SQLAlchemyInstrumentor().instrument(
            engine=engine.sync_engine, tracer_provider=telemetry.provider
        )
        try:
            repository = SqlAlchemyInvestigationRepository(engine)
            try:
                if configured.pulseflow_agent_env == "test":
                    await repository.create_schema_for_tests()
                await repository.check_ready()
                await repository.recover_interrupted()
            except Exception:
                raise RuntimeError("Agent database unavailable or schema not migrated") from None
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(10, connect=3, pool=3),
                limits=httpx.Limits(
                    max_connections=configured.pulseflow_agent_http_connections,
                    max_keepalive_connections=8,
                ),
            ) as client:
                HTTPXClientInstrumentor.instrument_client(
                    client, tracer_provider=telemetry.provider
                )
                guardrail = AzurePiiGuardrail(configured, client)
                investigator = GrowthInvestigator(
                    configured,
                    guardrail,
                    PulseFlowApiClient(configured, client, telemetry.metrics),
                    telemetry=telemetry,
                )
                service = InvestigationService(repository, investigator, guardrail)
                app.state.investigations = service
                app.state.ready = True
                try:
                    yield
                finally:
                    app.state.ready = False
                    await service.shutdown()
        finally:
            app.state.ready = False
            await engine.dispose()
            SQLAlchemyInstrumentor().uninstrument(engine=engine.sync_engine)
            telemetry.shutdown()

    app = FastAPI(title="PulseFlow Agent", lifespan=lifespan)
    app.state.ready = False
    app.state.streams = 0

    # 将准入拒绝统一映射为 429，并以 Retry-After 指示六十秒后重试。
    @app.exception_handler(AdmissionRejected)
    async def capacity_error(_request: Request, _error: AdmissionRejected) -> JSONResponse:
        return JSONResponse(
            status_code=429,
            content={"detail": "agent_capacity_exceeded"},
            headers={"Retry-After": "60"},
        )

    # 将请求模型校验错误转换为固定 422 响应，避免回显提交的敏感值。
    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": "invalid_investigation_request"})

    # 仅对内部 API 校验有长度上限的令牌，使用 compare_digest 比较；其他路径交给后续处理。
    @app.middleware("http")
    async def internal_auth(request: Request, call_next: RequestResponseEndpoint) -> Response:
        if not request.url.path.startswith("/internal/v1/"):
            return await call_next(request)
        configured: AgentSettings = request.app.state.settings
        expected = configured.pulseflow_agent_internal_token
        supplied = request.headers.get("X-PulseFlow-Agent-Token", "")
        if (
            expected is None
            or len(supplied) > 512
            or not compare_digest(supplied, expected.get_secret_value())
        ):
            return JSONResponse(status_code=401, content={"detail": "Unauthorized"})
        return await call_next(request)

    # 返回进程存活标记，不执行依赖检查。
    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    # 返回跨运行聚合质量指标；访问权限由内部 API 中间件保证。
    @app.get("/internal/v1/agent-quality")
    async def agent_quality() -> dict[str, float | None]:
        return telemetry.metrics.snapshot()

    # 检查应用就绪标记和数据库表可读性，未就绪或依赖异常统一返回 503。
    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        if not app.state.ready:
            raise HTTPException(status_code=503, detail="agent not ready")
        try:
            await app.state.investigations._repository.check_ready()
        except Exception:
            raise HTTPException(status_code=503, detail="agent not ready") from None
        return {"status": "ready"}

    # 启动后台调查并以 202 返回初始快照；PII 阻断映射为 422，容量异常保留，其他失败为 503。
    @app.post("/internal/v1/investigations/start", status_code=202)
    async def start_investigation(body: InvestigationRequest, request: Request) -> Investigation:
        try:
            return await request.app.state.investigations.start(body.question)  # type: ignore[no-any-return]
        except AdmissionRejected:
            raise
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 在原调查 ID 下恢复后台运行并允许更新范围，将不存在、占用及安全阻断映射为稳定 HTTP 状态。
    @app.post("/internal/v1/investigations/{investigation_id}/resume", status_code=202)
    async def resume_investigation(
        investigation_id: str, body: FollowUpRequest, request: Request
    ) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.start(body.question, investigation_id, body.scope)
        except AdmissionRejected:
            raise
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="investigation_busy") from None
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 取消本进程拥有的调查运行，分别处理资源缺失、运行归属冲突及依赖故障。
    @app.post("/internal/v1/investigations/{investigation_id}/cancel")
    async def cancel_investigation(investigation_id: str, request: Request) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.cancel(investigation_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="investigation_busy") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 校验调查存在并限制最多十六条 SSE 流，返回不缓存的业务状态流。
    @app.get("/internal/v1/investigations/{investigation_id}/events")
    async def events(investigation_id: str, request: Request) -> StreamingResponse:
        service: InvestigationService = request.app.state.investigations
        try:
            await service.get(investigation_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        if app.state.streams >= 16:
            raise AdmissionRejected()
        app.state.streams += 1

        # 将事件名和字符串字段封装为 SSE 帧，统一附加 investigation_id，不附带模型或业务正文。
        def frame(event: str, **data: str) -> str:
            payload = json.dumps({"investigation_id": investigation_id, **data})
            return f"event: {event}\ndata: {payload}\n\n"

        # 轮询持久化状态并按增量发出工具、证据、假设和提案事件；结束、断连或一百一十秒后退出，
        # finally 释放流计数。
        async def stream() -> AsyncIterator[str]:
            seen_tools = 0
            seen_evidence: set[str] = set()
            seen_hypotheses: dict[str, str] = {}
            seen_proposals: set[str] = set()
            started = monotonic()
            try:
                yield frame("investigation_started")
                while not await request.is_disconnected() and monotonic() - started < 110:
                    item = await service.get(investigation_id)
                    for name in item.tool_trajectory[seen_tools:]:
                        yield frame("tool_started", tool_name=name)
                    seen_tools = len(item.tool_trajectory)
                    for evidence in item.evidence:
                        if evidence.id not in seen_evidence:
                            yield frame("tool_completed", tool_name=evidence.tool_name)
                            yield frame("evidence_added", evidence_id=evidence.id)
                            seen_evidence.add(evidence.id)
                    for hypothesis in item.hypotheses:
                        version = hypothesis.updated_at.isoformat()
                        if seen_hypotheses.get(hypothesis.id) != version:
                            yield frame(
                                "hypothesis_changed",
                                hypothesis_id=hypothesis.id,
                                status=hypothesis.status,
                            )
                            seen_hypotheses[hypothesis.id] = version
                    for proposal in item.proposals:
                        if proposal.id not in seen_proposals:
                            yield frame("proposal_ready", proposal_id=proposal.id)
                            seen_proposals.add(proposal.id)
                    if item.status != "RUNNING":
                        yield frame(
                            "error"
                            if item.status in ("FAILED", "CANCELLED")
                            else "diagnosis_ready",
                            status=item.status,
                        )
                        return
                    yield ": heartbeat\n\n"
                    await asyncio.sleep(1)
                yield frame("error", status="stream_window_expired")
            except Exception:
                yield frame("error", status="agent_unavailable")
            finally:
                app.state.streams -= 1

        return StreamingResponse(
            stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    # 同步创建并运行调查后返回最终快照，安全阻断、容量限制与其他失败使用稳定状态码表达。
    @app.post("/internal/v1/investigations")
    async def investigate(body: InvestigationRequest, request: Request) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.create(body.question)
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except AdmissionRejected:
            raise
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 读取完整调查快照，未找到返回 404，仓储异常返回不含内部详情的 503。
    @app.get("/internal/v1/investigations/{investigation_id}")
    async def get_investigation(investigation_id: str, request: Request) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.get(investigation_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 同步执行后续调查轮次与可选范围变更，区分不存在、运行冲突、PII 阻断和服务故障。
    @app.post("/internal/v1/investigations/{investigation_id}/follow-up")
    async def follow_up(
        investigation_id: str, body: FollowUpRequest, request: Request
    ) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.follow_up(investigation_id, body.question, body.scope)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="investigation_busy") from None
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except AdmissionRejected:
            raise
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 使用请求中操作员 ID 和明确授权的促销事实生成提案；前置诊断或状态不满足时返回 409。
    @app.post("/internal/v1/investigations/{investigation_id}/proposal")
    async def propose(
        investigation_id: str, body: ProposalRequest, request: Request
    ) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.propose(
                investigation_id, body.question, body.owner_id, body.promotion_facts
            )
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="proposal_not_available") from None
        except PiiBlockedError as error:
            raise HTTPException(status_code=422, detail=error.reason) from None
        except AdmissionRejected:
            raise
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 读取提案记录及其同范围证据 ID 快照，供 Java 验证后创建草稿。
    @app.get("/internal/v1/proposals/{proposal_id}")
    async def get_proposal(proposal_id: str, request: Request) -> ProposalRead:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service._repository.load_proposal(proposal_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 将 Java 已创建的 draft_id 幂等回写提案；草稿绑定冲突返回 409。
    @app.put("/internal/v1/proposals/{proposal_id}/draft")
    async def mark_proposal_draft(
        proposal_id: str, body: DraftCreatedRequest, request: Request
    ) -> ProposalRecord:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service._repository.mark_draft_created(proposal_id, body.draft_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except InvestigationConflictError:
            raise HTTPException(status_code=409, detail="proposal_status_conflict") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    FastAPIInstrumentor.instrument_app(
        app,
        tracer_provider=telemetry.provider,
        excluded_urls="health/live,health/ready",
        http_capture_headers_server_request=[],
        http_capture_headers_server_response=[],
        http_capture_headers_sanitize_fields=[".*"],
        exclude_spans=["receive", "send"],
    )
    return app


app = create_app()
