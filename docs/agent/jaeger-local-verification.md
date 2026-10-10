# Jaeger 本地验收记录

运行日期：2026-10-10（Asia/Shanghai）。基线：`origin/main` 的 `33a662520b450195011bdc8d7f5f5c0a91dd0e83`。独立工作区未改动原目录的未提交文件。

## 实际接收结果

Docker Desktop 4.73.0 / Engine 29.4.3 / Compose v5.1.3，Jaeger 2.17.0。两套 MySQL、Redis、Kafka、Java、Agent、迁移及 Jaeger 实际运行；Jaeger API 返回 200，本地浏览器打开 UI。

确定性模型夹具使用已有 OfflinePolicy + FunctionModel；Java 聚合事实来自真实内部 API 和业务 MySQL。默认 TestModel Investigation 另行验证，未调用 Tool，诚实返回证据不足。额外运行已有 `testing/agent_smoke.py`，验证 Java 登录、业务 Investigation 入口、ownership、PII、follow-up 和 SSE。

| 项目 | 结果 |
| --- | --- |
| Compose profile、端口、原网络/数据库配置契约 | PASS |
| Jaeger 回环 UI / Query API | PASS |
| FastAPI / Pydantic AI Agent / FunctionModel / Tool | PASS |
| Python HTTPX → Java HTTP 相同 Trace ID 和直接父子关系 | PASS |
| Java `agent-tools.metric-query` → MySQL JDBC 祖先关系 | PASS |
| Agent SQLAlchemy → 专用 MySQL | PASS |
| Jaeger 返回内容的敏感哨兵、Token、Header、events 检查 | PASS |
| ERROR 状态及被拒请求的 HTTP 401 | PASS |
| stop / down / 重启后验收，4 个原数据卷全部保留 | PASS |
| 真实模型 + Azure PII 端到端 | NOT_RUN，环境未配置凭据 |

## 可查询的脱敏样例

Investigation：`c6df6d6f-408b-4543-8a3a-29bd6427444a`。

完整链路 Trace：`8cb7bc78aecd4c2828cfba954ae5c295`（[本地打开](http://localhost:16686/trace/8cb7bc78aecd4c2828cfba954ae5c295)）。默认 TestModel：`26e4543ff4de2b067c48a3ef0a69b6c6`。错误 Trace：`edbb60ba1241270767d55eb7d11f86ea`。这些都是 SDK 实际生成的标识，不包含用户内容；Jaeger 重启后需重新执行 `verify` 获取新 ID。

```text
pulseflow-agent: acceptance.investigation（测试调用方真实导出的 root）
└─ POST /internal/v1/investigations（FastAPI）
   ├─ Agent MySQL connect / SELECT / INSERT / UPDATE
   └─ agent.investigation
      └─ invoke_agent growth_investigator
         ├─ chat function:offline:
         ├─ execute_tool query_metric
         │  ├─ Agent MySQL 操作
         │  └─ POST（HTTPX client）
         │     └─ pulseflow-java: POST /internal/v1/agent-tools/metrics/query
         │        └─ agent-tools.metric-query
         │           ├─ SELECT pulseflow_test.delivery_record（JDBC）
         │           └─ SELECT pulseflow_test（JDBC）
         └─ chat function:offline:
```

没有为 MySQL 创建虚假的第三个服务。数据库操作 Span 属于执行查询的 Python/Java 服务。模型是离线夹具，Trace 的模型步骤不代表真实 Provider 网络请求或真实模型推理质量。

## 构建与测试

- Java 本地 Maven package、完整 `mvn verify` 成功。初次 verify 中 Docker 条件测试跳过，随后显式启用 Docker，`AgentTelemetryTest` 2 项及 `AgentTelemetryMysqlIT` 2 项均通过，MySQL 项没有跳过。
- Python 最终离线回归 **47 passed / 2 skipped**；跳过项为真实 Provider 和独立 MySQL workspace 条件测试（实际 Compose 验收已覆盖 Agent MySQL）。Ruff 及 mypy（34 个源/测试文件）通过。
- Python OTLP HTTP 测试检查真实 protobuf bytes，确认 `/v1/traces` 只追加一次，移除内容、认证、SQL、异常、事件和敏感 Resource，并保留 ERROR、Trace ID、父 Span ID。
- Java SafeSpanExporter 原有异常/SQL/Token 测试继续通过，实际 Jaeger 查询验证两端输出。
- Docker Hub 认证端点曾超时；公共缓存最终提供相同固定版本的 Python、JRE、Maven 镜像。原始 Java 多阶段 Dockerfile、Agent Dockerfile 完整构建均成功，默认 `run.ps1 start` 命令通过。最终验收针对这些完整构建的镜像执行；没有更改项目 Dockerfile。

原始日志在已忽略的 `testing/reports/jaeger-acceptance.log`、`jaeger-maven.log`、`jaeger-java-telemetry.log`。验收报告只输出标识、Span 名、服务、父 ID 和耗时。没有提交密钥、真实用户数据、完整业务响应或模型正文。

## 改动清单

新增：

- `testing/docker-compose.observability.yml`：固定 Jaeger 版本、显式 profile、回环 UI、两端 OTLP 和独立验收容器。
- `testing/observability/run.ps1`：一条命令启动、验收、停止及保留数据卷的清理。
- `testing/observability/check_config.py`：默认关闭、网络、权限、端口和 OTLP 地址契约检查。
- `testing/observability/verify.py`：真实 HTTP/Java/MySQL/Jaeger 的确定性 Investigation 和错误/隐私验收。
- `docs/agent/jaeger-local-tracing.md`：中文启动与排查指南。
- `docs/agent/jaeger-local-verification.md`：本记录。

更新：

- `pulseflow-agent/tests/observability/test_tracing.py`：真实 OTLP HTTP protobuf 隐私和父子关系测试。
- `pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentTelemetryTest.java`：补全敏感哨兵与 ERROR 保留断言。
- `docs/agent/agent-evaluation.md`、`testing/README.md`：本地指南入口。

未改动生产代码、默认生产配置、业务决策、权限边界、现有 tracing 架构或 SafeSpanExporter 过滤逻辑。
