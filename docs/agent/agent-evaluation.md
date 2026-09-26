# Agent 评测与追踪（Phase 5）

## 评测集与边界

`pulseflow-agent/tests/evals/cases.json` 包含 22 个 Case：正常异常定位、无异常、低样本/零分母/零基线、冲突 Evidence、假设推翻、空数据、缺少绩效摘要、Java timeout/4xx/5xx、无效响应/意外 PII 字段、PII 输入和预算耗尽。每个 Case 定义用户目标、Java fixture、证据约束、禁止结论、所需 Tool family 和最大 Tool 调用数。

普通 CI 使用官方 `FunctionModel` 与 MockTransport；断言 Tool family、证据引用和预算，不锁定完整 Tool 顺序。`tests/conftest.py` 禁止真实模型请求。CI 上传 `agent-eval-report`，只含 Case ID、状态、Tool 名和指标，不保存用户文本、模型输出或原始数据。

离线 fake policy 位于测试 harness，生产 Agent 没有该流程。离线评测验证控制流和安全边界，不能证明真实模型的语义质量。`unsupported_claim_rate` 由每个 Case 的禁止结论和新数值检查计算；比率转换成百分比允许通过。它仍不是完整的语义、因果或数值幻觉检测器。

真实模型评测需显式配置真实 Provider、Azure PII 和内部测试 Token，然后设置 `REAL_AGENT_EVAL=true` 执行 `uv run pytest tests/evals/test_dataset.py -k real_provider -q`。它使用 fixture Java 数据，可能产生模型与 Azure 费用，默认跳过，不阻塞普通 CI。Phase 5 本次没有运行真实模型评测。

工具扩展必须由真实 Eval 的信息缺口证明。现有六个业务 Tool 覆盖当前已确认能力；本阶段未增加 audience/funnel/frequency Tool，也未增加没有权威事实的维度。漏斗的事件映射、窗口和去重契约尚未建立，不能把现有同窗比率冒充漏斗。

## 质量指标

`GET /internal/v1/agent-quality` 使用相同机器 Token，提供当前进程的聚合快照：

`investigation_success_rate`、`evidence_reference_valid_rate`、`unsupported_claim_rate`、`avg_tool_calls`、`avg_model_requests`、`avg_latency`、`avg_tokens`、`avg_cost`、`budget_exhausted_rate`、`java_tool_error_rate`、`pii_block_rate`。

请求/Tool/token 数来自 Pydantic AI `RunUsage`，模型成本使用官方 `ModelResponse.cost()` 定价结果；无法定价时为 null，不假定为零。延迟取官方 Agent span 的时间。成功率表示完成 Diagnosis 的比例，不等同于评测通过率。运行时无法自动标注语义是否 unsupported，所以该项为 null；评测报告给出 Case 约束下的测量值。指标目前只在进程内聚合，重启归零，评测报告可持续留存。成本只计算模型费用，不含 Azure。

## OpenTelemetry

Python 使用官方 FastAPI、HTTPX、SQLAlchemy 与 Pydantic AI instrumentation；Pydantic AI `include_content=False` 和 `include_binary_content=False`。SQLAlchemy 锁定在官方 instrumentor 支持的 2.0 系列。W3C `traceparent` 随 Java Tool 请求传播，Evidence 保存同一 trace ID 和 Java queryId。Java 使用锁定版本的 OpenTelemetry Spring Boot starter，HTTP server、`@WithSpan` 业务服务和 JDBC span 接续该 trace。

两端 exporter 使用属性白名单，删除消息、Tool 参数/返回、SQL 文本、异常消息/堆栈、HTTP 认证头、链接事件和非白名单 Resource 属性；不记录 API Key、Internal Token、用户问题或原始事件。测试用敏感哨兵验证导出结果。追踪不能绕过 PII 出站检查。

Python 未配置 `PULSEFLOW_AGENT_OTEL_ENDPOINT` 时不向外部 exporter 发送数据；配置为 collector HTTP 基地址后使用 `/v1/traces`。Java 默认 `PULSEFLOW_OTEL_DISABLED=true`；启用时设置 `PULSEFLOW_OTEL_DISABLED=false`、`OTEL_TRACES_EXPORTER=otlp`、`OTEL_EXPORTER_OTLP_ENDPOINT` 和 `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`。两端使用相同 collector。Collector、存储与网络权限由部署方配置，不在代码中写入认证秘密。

测试分别验证 Python HTTP → 官方 Agent/Model/Tool → HTTPX traceparent，以及 Java HTTP → 服务 → JDBC；Java MySQL 驱动的相同 trace 测试在 Docker CI 中运行。删除或关闭 Agent/OTel 不影响 Java 的确定性业务执行。
