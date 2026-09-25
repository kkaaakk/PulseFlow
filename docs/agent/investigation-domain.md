# Growth Investigation Agent（Phase 3）

Phase 3 使用一个 Pydantic AI Agent。它根据现有 Evidence 自主决定是否继续，以及下一步调用哪一个 Java 只读 Tool；生产代码没有固定的 Tool 顺序。当前 workspace 仅在一次调查的内存中存在，尚不支持跨请求续查或持久化。

## 内部调用

`POST /internal/v1/investigations` 接收 `{ "question": "..." }`，要求 `X-PulseFlow-Agent-Token`。鉴权在请求体解析前执行；浏览器不能持有此机器 Token，也不能提交 `operatorId`。响应只包含 `diagnosis`、`evidence` 和 `tool_trajectory`，不包含隐藏推理、系统 Prompt 或模型原始消息。健康端点保持 `/health/live` 与 `/health/ready`；readiness 现在要求 Java Tool Token 已配置，不调用真实模型或 Java 服务。

## 调查与证据

六个 Python Tool 与 [Java Tool 契约](agent-tool-contract.md)一一对应：`query_metric`、`compare_metric`、`breakdown_metric`、`get_campaign_performance`、`get_attribution_breakdown`、`preview_audience`。Pydantic 校验参数，HTTPX 客户端只访问固定 Java 路径；Java 响应经严格 Pydantic Schema 校验，未知字段、指标错配或越过请求行数上限会被拒绝。Java 4xx、5xx 与超时转为不含原始数据的固定错误码。

每次成功调用自动创建 `Evidence`：`id`、Tool 名、Java `source`、由返回聚合数值确定性生成的 `observation`、安全查询摘要、`data_version`、警告、`trace_id`、采集时间和 Java `queryId`。完整 Campaign DSL 不进入 Evidence 查询摘要，原始用户行不进入 workspace。Tool 返回模型前再次经过 PII 出站检查。失败调用记录在轨迹中，但不伪造 Evidence。

最终 `Diagnosis` 含 `status`、摘要、Finding、Evidence ID、未决问题、置信度和建议。输出校验器要求所有 Evidence ID 来自本次 workspace，Finding 引用也必须出现在顶层列表中。没有业务 Evidence 不能给出 `DIAGNOSED`；`INSUFFICIENT_EVIDENCE` 必须低置信度并列明未决问题。用量预算耗尽时返回低置信度、证据不足结果。

## 离线轨迹

[`tests/agent/trajectories.json`](../../pulseflow-agent/tests/agent/trajectories.json)保存了同一用户问题模板在三组不同 Java 聚合响应下的预期 Tool 路径：

| 情景 | 第三步 |
|---|---|
| CTR 下降、点击后转化稳定 | 按 Channel 拆解 CTR |
| CTR 稳定、点击后转化下降 | 查看归因拆解 |
| 两者稳定 | 停止，不继续调用 Tool |

这些场景使用官方 `FunctionModel` 和 HTTPX MockTransport，CI 不请求真实模型或 Azure。它们验证动态 Tool 选择、证据流和输出校验；真实模型的诊断质量评测属于 Phase 5。

## 当前边界

Agent 不持有业务 DB/Redis 凭证，不提供 SQL、写 Campaign、确认或触达 Tool。Java 保持指标与公式权威。调查状态尚未持久化，用户改变范围后的续查属于 Phase 4。生产 ingress 仍需阻断公网访问内部端点，网络部署规则见 [Java Tool 契约](agent-tool-contract.md)。
