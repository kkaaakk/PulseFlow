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

from pulseflow_agent.agent.growth_investigator import GrowthInvestigator, create_model
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


# 经过空白清理和长度约束的调查问题输入。
class InvestigationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
    ]


# 追问请求，可附带新的调查范围。
class FollowUpRequest(InvestigationRequest):
    scope: Annotated[
        str | None, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ] = None


# 方案生成请求，附带操作员授权的优惠事实。
class ProposalRequest(InvestigationRequest):
    owner_id: int = Field(gt=0)
    promotion_facts: list[PromotionFact] = Field(default_factory=list, max_length=10)


# Java 已创建草稿的回写请求，仅携带正数草稿 ID。
class DraftCreatedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    draft_id: int = Field(gt=0)


# 创建 FastAPI 应用，注册内部鉴权、健康检查、调查接口和业务进度 SSE。
def create_app(
    settings: AgentSettings | None = None, trace_runtime: Telemetry | None = None
) -> FastAPI:
    telemetry = trace_runtime or Telemetry()

    # 启动时检查存储、恢复中断调查并构建依赖；退出时关闭任务、数据库和遥测。
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
                    model=create_model(configured, client),
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

    # 把容量拒绝转为 429，告知客户端六十秒后重试。
    @app.exception_handler(AdmissionRejected)
    async def capacity_error(_request: Request, _error: AdmissionRejected) -> JSONResponse:
        return JSONResponse(
            status_code=429,
            content={"detail": "agent_capacity_exceeded"},
            headers={"Retry-After": "60"},
        )

    # 统一返回不含原始输入的 422 错误，避免校验细节泄露正文。
    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": "invalid_investigation_request"})

    # 对内部路由以恒定时间比较校验机器令牌，失败直接返回 401。
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

    # 返回进程存活状态，不访问数据库或触发调查。
    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    # 返回聚合质量指标，不包含单次调查的原始输入输出。
    @app.get("/internal/v1/agent-quality")
    async def agent_quality() -> dict[str, float | None]:
        return telemetry.metrics.snapshot()

    # 检查启动状态及表可查询性，未就绪或存储异常返回 503。
    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        if not app.state.ready:
            raise HTTPException(status_code=503, detail="agent not ready")
        try:
            await app.state.investigations._repository.check_ready()
        except Exception:
            raise HTTPException(status_code=503, detail="agent not ready") from None
        return {"status": "ready"}

    # 以 202 接受新调查并后台执行，将安全检查失败和服务异常转为固定错误。
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

    # 在原 ID 上异步追问并可切换范围，映射未找到、运行冲突和 PII 拦截错误。
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

    # 取消调查并返回快照，将归属进程冲突与未找到映射为接口错误。
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

    # 验证调查存在并限制连接数，返回不缓存的业务进度 SSE。
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

        # 把事件名、调查 ID 和安全状态字段编码为 SSE 帧，不输出推理正文。
        def frame(event: str, **data: str) -> str:
            payload = json.dumps({"investigation_id": investigation_id, **data})
            return f"event: {event}\ndata: {payload}\n\n"

        # 轮询快照并增量发送工具、证据、假设和方案事件，终态或超时后释放名额。
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

    # 同步创建并完成调查，安全检查或依赖失败时返回固定错误。
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

    # 按 ID 查询详情，区分记录不存在与服务不可用。
    @app.get("/internal/v1/investigations/{investigation_id}")
    async def get_investigation(investigation_id: str, request: Request) -> Investigation:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service.get(investigation_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 同步追问并可更新范围，将并发状态冲突转换为 409。
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

    # 接收 Java 传入的操作员和优惠事实，基于证据生成方案并返回结果。
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

    # 读取方案及可引用证据，供已鉴权的 Java 客户端创建草稿。
    @app.get("/internal/v1/proposals/{proposal_id}")
    async def get_proposal(proposal_id: str, request: Request) -> ProposalRead:
        try:
            service: InvestigationService = request.app.state.investigations
            return await service._repository.load_proposal(proposal_id)
        except InvestigationNotFoundError:
            raise HTTPException(status_code=404, detail="not_found") from None
        except Exception:
            raise HTTPException(status_code=503, detail="agent_unavailable") from None

    # 记录 Java 草稿 ID，重复关联不同草稿时返回 409。
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
