"""Official OTel instrumentation with content capture disabled."""

from collections.abc import Sequence
from threading import Lock

from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan, SpanProcessor, TracerProvider
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
    SpanExportResult,
)
from opentelemetry.trace import Status
from pydantic_ai.capabilities import Instrumentation
from pydantic_ai.models.instrumented import InstrumentationSettings
from pydantic_ai.usage import RunUsage


class QualityMetrics:
    """Cross-run aggregate values; run usage comes from Pydantic AI, not a token counter."""

    # 初始化受线程锁保护的跨运行计数、耗时和成本累积值，不保存业务内容。
    def __init__(self) -> None:
        self._lock = Lock()
        self.runs = self.successes = self.budgets = self.pii_blocks = 0
        self.java_calls = self.java_errors = self.reference_checks = self.reference_errors = 0
        self.tool_calls = self.model_requests = self.tokens = 0
        self.latency_total = 0.0
        self.latency_count = 0
        self.cost_total = 0.0
        self.cost_count = 0

    # 按终态累计运行数、成功与阻断情况；可选 usage 和 cost 来自实际模型运行，缺失时不伪造消耗。
    def record_status(
        self, status: str, usage: RunUsage | None = None, cost: float | None = None
    ) -> None:
        with self._lock:
            self.runs += 1
            self.successes += status == "COMPLETED"
            self.budgets += status == "BUDGET_EXHAUSTED"
            self.pii_blocks += status == "PII_BLOCKED"
            if usage is not None:
                self.tool_calls += usage.tool_calls
                self.model_requests += usage.requests
                self.tokens += usage.input_tokens + usage.output_tokens
            if cost is not None:
                self.cost_total += cost
                self.cost_count += 1

    # 累计一次 Java 调用及其是否失败，更新操作受锁保护。
    def java_call(self, error: bool) -> None:
        with self._lock:
            self.java_calls += 1
            self.java_errors += error

    # 记录一次证据引用校验及其有效性，用于统计引用有效率。
    def reference_check(self, valid: bool) -> None:
        with self._lock:
            self.reference_checks += 1
            self.reference_errors += not valid

    # 在 HTTP 调用已计数后补记一次响应业务契约错误，不重复增加调用次数。
    def java_contract_error(self) -> None:
        with self._lock:
            self.java_errors += 1

    # 累计一次以秒计的 Agent 运行耗时与样本数。
    def latency(self, seconds: float) -> None:
        with self._lock:
            self.latency_total += seconds
            self.latency_count += 1

    # 在锁内计算质量比率和平均值；缺少样本或无法从运行推断的语义质量指标返回 None。
    def snapshot(self) -> dict[str, float | None]:
        with self._lock:
            return {
                "investigation_success_rate": self.successes / self.runs if self.runs else None,
                "evidence_reference_valid_rate": (
                    1 - self.reference_errors / self.reference_checks
                    if self.reference_checks
                    else None
                ),
                # Semantic claim quality requires an evaluated case, not a runtime guess.
                "unsupported_claim_rate": None,
                "avg_tool_calls": self.tool_calls / self.runs if self.runs else None,
                "avg_model_requests": self.model_requests / self.runs if self.runs else None,
                "avg_latency": self.latency_total / self.latency_count
                if self.latency_count
                else None,
                "avg_tokens": self.tokens / self.runs if self.runs else None,
                "avg_cost": self.cost_total / self.cost_count if self.cost_count else None,
                "budget_exhausted_rate": self.budgets / self.runs if self.runs else None,
                "java_tool_error_rate": self.java_errors / self.java_calls
                if self.java_calls
                else None,
                "pii_block_rate": self.pii_blocks / self.runs if self.runs else None,
            }


class AgentLatencyProcessor(SpanProcessor):
    # 绑定耗时指标收集器，供 Agent 根 span 完成时写入样本。
    def __init__(self, metrics: QualityMetrics) -> None:
        self.metrics = metrics

    # 仅从 invoke_agent span 的起止纳秒计算运行耗时，忽略其他 span 和缺少时间的记录。
    def on_end(self, span: ReadableSpan) -> None:
        attributes = span.attributes or {}
        if (
            attributes.get("gen_ai.operation.name") == "invoke_agent"
            and span.start_time is not None
            and span.end_time is not None
        ):
            self.metrics.latency((span.end_time - span.start_time) / 1_000_000_000)


class SafeSpanExporter(SpanExporter):
    """Export only operational metadata, even if an instrumentor captures content."""

    # 保存真实导出器，所有 span 先经过当前包装层的隐私裁剪。
    def __init__(self, wrapped: SpanExporter) -> None:
        self.wrapped = wrapped

    # 重建只含白名单运行属性和服务标识的 span，移除事件、链接与状态描述后交给底层导出器。
    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        allowed = {
            "gen_ai.operation.name",
            "gen_ai.request.model",
            "gen_ai.response.model",
            "gen_ai.tool.name",
            "gen_ai.agent.name",
            "gen_ai.agent.call.id",
            "gen_ai.conversation.id",
            "investigation.id",
            "evidence.id",
            "tool.name",
            "http.method",
            "http.request.method",
            "http.status_code",
            "http.response.status_code",
            "http.route",
            "server.address",
            "server.port",
            "url.scheme",
            "db.system",
            "db.system.name",
            "db.name",
            "db.namespace",
            "db.operation.name",
            "agent.request_count",
            "agent.tool_call_count",
            "agent.model_provider",
            "pii.result",
        }
        safe = [
            ReadableSpan(
                name=span.name,
                context=span.context,
                parent=span.parent,
                resource=Resource(
                    {
                        key: value
                        for key, value in span.resource.attributes.items()
                        if key in {"service.name", "service.version"}
                        or key.startswith("telemetry.sdk.")
                    }
                ),
                attributes={
                    key: value
                    for key, value in (span.attributes or {}).items()
                    if key in allowed
                    or key.startswith(("gen_ai.usage.", "gen_ai.aggregated_usage."))
                },
                events=(),
                links=(),
                kind=span.kind,
                status=Status(span.status.status_code),
                start_time=span.start_time,
                end_time=span.end_time,
                instrumentation_scope=span.instrumentation_scope,
            )
            for span in spans
        ]
        return self.wrapped.export(safe)

    # 关闭被包装的实际导出器并释放其资源。
    def shutdown(self) -> None:
        self.wrapped.shutdown()


class Telemetry:
    # 创建或复用 TracerProvider，安装耗时处理器，并按可选 endpoint 或 exporter 配置经过隐私过滤
    # 的导出链。
    def __init__(
        self,
        endpoint: str | None = None,
        provider: TracerProvider | None = None,
        exporter: SpanExporter | None = None,
    ) -> None:
        self.metrics = QualityMetrics()
        self.provider = provider or TracerProvider(
            resource=Resource.create(
                {
                    "service.name": "pulseflow-agent",
                }
            )
        )
        self.provider.add_span_processor(AgentLatencyProcessor(self.metrics))
        if endpoint:
            self.configure_endpoint(endpoint)
        if exporter is not None:
            self.provider.add_span_processor(SimpleSpanProcessor(SafeSpanExporter(exporter)))

    # 创建关闭文本及二进制内容采集的 Pydantic AI instrumentation，使用当前 provider。
    def agent_instrumentation(self) -> Instrumentation:
        return Instrumentation(
            settings=InstrumentationSettings(
                tracer_provider=self.provider,
                include_content=False,
                include_binary_content=False,
                version=5,
            )
        )

    # 把 /v1/traces OTLP HTTP 导出器包装为安全批量导出流程并注册到 provider。
    def configure_endpoint(self, endpoint: str) -> None:
        self.provider.add_span_processor(
            BatchSpanProcessor(
                SafeSpanExporter(
                    OTLPSpanExporter(
                        endpoint=endpoint.rstrip("/") + "/v1/traces",
                    )
                )
            )
        )

    # 关闭当前 TracerProvider，刷新并释放其处理器与导出器。
    def shutdown(self) -> None:
        self.provider.shutdown()
