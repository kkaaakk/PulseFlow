# Windows 本地 OpenTelemetry + Jaeger

Jaeger 把同一个请求的 Span 按父子关系和时间排列，用于查看 Agent、Java 和数据库各步骤耗时与错误。本地固定使用官方 `cr.jaegertracing.io/jaegertracing/jaeger:2.17.0`（[官方说明](https://www.jaegertracing.io/docs/2.17/getting-started/)），直接接收 OTLP HTTP，不额外部署 Collector。Trace 存在内存中，Jaeger 重启后清空。

## 启动与离线验收

前提：Docker Desktop 的 Linux containers 引擎已运行，PowerShell 位于仓库根目录；首次启动需要下载镜像和构建依赖。

```powershell
# 构建并启动 Java、Agent、两套 MySQL、Redis、Kafka、迁移及 Jaeger
.\testing\observability\run.ps1 start

# 本次机器已构建好镜像时可跳过重新构建（例如 Docker Hub 暂时不可达）
.\testing\observability\run.ps1 start -NoBuild

# 执行确定性离线 Investigation，并从 Jaeger 查询、验证实际收到的 Span
.\testing\observability\run.ps1 verify

Start-Process 'http://localhost:16686'
```

脚本在未配置本地 Token 文件时生成随机机器 Token，写入已忽略的 `.env.jaeger.local`，供 Java 与 Agent 共用。保留此文件供后续启动；不要提交或输出其内容。已有 `PULSEFLOW_AGENT_INTERNAL_TOKEN` 环境变量优先于文件。Java 用户登录及 Proposal → Draft 权限不变。

`start` 等价于以下 Compose 命令（Token 文件需已创建）：

```powershell
docker compose --env-file .env.jaeger.local `
  -f testing/docker-compose.test.yml `
  -f testing/docker-compose.observability.yml `
  --profile agent --profile observability up -d --build java agent jaeger
```

只有显式启用 `observability` profile 才启动 Jaeger。**覆盖文件本身会为 Java/Agent 配置导出地址**，因此启用追踪必须同时带上该文件和 profile；关闭时只使用原始 Compose 文件。原始开发/CI 命令、生产默认关闭策略均未修改。

## 离线验收的含义

`verify` 只允许运行中的 Agent 使用 `test` 模型，普通离线验收不会调用收费模型或 Azure。它先对运行中的 Agent 发起真实 HTTP Investigation。默认 `TestModel` 不调用业务 Tool，返回 `INSUFFICIENT_EVIDENCE`，这一步不能证明 Python → Java → MySQL。

随后一次性 `trace-check` 容器复用 `tests/evals/harness.py` 的 `OfflinePolicy` / 官方 `FunctionModel`，启动同一个 `create_app` FastAPI，在测试进程的既有模型注入点替换模型。**Java API、机器 Token 校验、PII guardrail、业务 Service、MySQL、HTTPX、OTLP exporter 均真实执行**，没有 MockTransport 或伪造业务响应。运行中的 Agent 模型与代码不变。测试只读取 Java 聚合事实，并在专用 Agent 数据库保存合成 Investigation；业务数据库没有数据写入或 fixture 灌入。

报告输出 Investigation ID、Trace ID、服务、Span ID、父 ID 和耗时，并检查：

- FastAPI → Agent → 模型/Tool → HTTPX → Java HTTP → `@WithSpan` Service → JDBC。
- Trace ID 一致；Java HTTP 的父 Span 是 Python HTTPX；JDBC 的祖先包括 Java Service；所有父 Span 可在 Trace 内找到。
- Agent 自身的 SQLAlchemy/MySQL Span。
- 敏感哨兵、内部 Token、SQL 内容、异常事件、HTTP Header 未出现在 Jaeger 查询结果中。
- 独立错误 Trace 保留 ERROR；错误 Token 被 Java 以 401 拒绝，能看到相关 HTTP 状态。

输出 `PASS` 才表示 Jaeger 已实际收到并通过检查。离线策略不能证明真实模型的诊断质量；真实数据库没有业务样本时，也不能据此断言转化率下降的原因。

需要保存脱敏验收结果时：

```powershell
.\testing\observability\run.ps1 verify | Tee-Object testing/reports/jaeger-local.txt
```

## 查询 Investigation 与查看错误

打开 [Jaeger UI](http://localhost:16686)，在顶部 Trace ID 搜索框粘贴验收报告的 `trace_id`，或打开 `http://localhost:16686/trace/<trace_id>`。

也可以选择 Service `pulseflow-agent`、Lookback `Last Hour`，在 Tags 输入 `investigation.id=<Investigation UUID>` 后点击 **Find Traces**。已有 Investigation 的 Evidence 也保存 `trace_id`；没有 Tool/Evidence 的默认 TestModel 可用验收报告的 `default_testmodel_trace_id`，或按时间与 Operation 搜索。

时间轴横条显示各步骤开始时间和持续时间。展开 `execute_tool query_metric`，依次查看 Python `POST`、Java `POST /internal/v1/agent-tools/metrics/query`、`agent-tools.metric-query` 和 `SELECT ...` JDBC Span。MySQL 不作为独立应用导出服务；JDBC Span 归属 `pulseflow-java`，Agent 数据库 Span 归属 `pulseflow-agent`。

点击 Span 查看 Tags、Duration、服务和父子关系。按 `error=true` 搜索失败 Trace；HTTP 4xx 的 Java server Span 可能没有 ERROR 标记，此时查看 `http.response.status_code` / `http.status_code`。验收报告的 `error_trace_id` 提供可重复的错误示例。追踪会有意删除异常正文/堆栈及 SQL 原文，使用步骤、错误状态和耗时定位问题。

## Trace 如何关联及网络边界

Python 复用 FastAPI、Pydantic AI、HTTPX、SQLAlchemy 的现有 instrumentation。`PulseFlowApiClient` 传播 W3C `traceparent`，HTTPX 创建实际 client Span；Java OTel Spring Boot starter 接收它，`@WithSpan` 与 JDBC 接续相同 Trace ID。异步 Investigation 由现有上下文传播接续，不增加另一套 tracing。

Python 的 `PULSEFLOW_AGENT_OTEL_ENDPOINT=http://jaeger:4318` 是基地址，现有代码只追加一次 `/v1/traces`。Java 使用：

```text
PULSEFLOW_OTEL_DISABLED=false
OTEL_TRACES_EXPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

Jaeger 的 OTLP 端口只在 Docker 网络使用，没有发布到主机。Web UI 仅绑定 `127.0.0.1:16686`。Jaeger 在 `agent-internal` 接收两端导出，另有仅它自己加入的 `observability-ui` 网络，使 Docker Desktop 可以发布回环 UI 端口。Agent、Java、数据库原有网络成员、数据库账号权限及 Agent 的只读文件系统不变，Jaeger 不加入业务数据库的 default 网络。

两端已有 SafeSpanExporter 白名单继续生效：移除内容属性、SQL/参数、认证头、异常消息/堆栈、events/links、非白名单 Resource，保留必要元数据和错误状态。Pydantic AI 的内容采集仍关闭。不要开启额外的正文或 Header 捕获。

## 停止与清理

```powershell
# 只停止 Jaeger；Java/Agent 仍运行，可能暂时记录导出连接失败
.\testing\observability\run.ps1 stop

# 停止并删除本测试栈容器和网络，保留所有已有数据卷
.\testing\observability\run.ps1 down
```

不要使用 `down -v`、`docker volume prune` 或删库命令。一次性验收容器由 `--rm` 自动清理；合成 Investigation 历史保留在 Agent 库，业务库未被验收写入。Jaeger 无持久数据卷，删除它只清除临时 Trace。

彻底关闭追踪并回到原始离线开发栈：

```powershell
.\testing\observability\run.ps1 down
docker compose --env-file .env.jaeger.local -f testing/docker-compose.test.yml `
  --profile agent up -d --build java agent
```

## 排查与自动化测试

- UI 不通：检查 Docker Desktop、端口 16686 冲突；`docker port pulseflow-test-jaeger-1` 应显示 `127.0.0.1:16686`。仅 internal 网络无法在部分 Docker Desktop 版本发布主机端口，所以不要删除独立 UI 网络。
- 无 Trace：确认同时使用覆盖文件和两个 profile；导出地址必须用 Docker DNS `jaeger`，不能用容器内的 `localhost`。等待批处理导出几秒，查看服务日志中的连接错误。不要打印 `docker compose config` 的完整环境变量或 `docker inspect` 的环境变量。
- 只有 Python Span：默认 TestModel 不调用 Tool，执行 `verify`。有 HTTPX 但无 Java Span 时检查 Java 是否启用 OTel、机器 Token 是否一致、Java 是否已启动。
- 只有 HTTP/Service、没有 JDBC：确认选中业务 Tool Trace，JDBC Span 的服务名是 `pulseflow-java`；本验收必须执行指标查询才能通过。
- `verify` 失败：检查输出的非敏感断言名、Agent readiness、Java `/actuator/health` 和 Jaeger `/api/services`。不会把容器启动成功当作验收通过。
- Docker Hub 下载失败：检查 Docker Desktop 代理/网络。此次认证端点曾超时，公共 `mirror.gcr.io/library/...` 缓存最终提供了相同固定版本的 Python、JRE、Maven 基础镜像；随后原始 Dockerfile 的完整构建和默认 `start` 命令均成功。已有镜像时可用 `start -NoBuild`；没有修改项目 Dockerfile 或引入非官方基础镜像。
- XXL-JOB admin 未启动：本地可能出现注册失败日志；不妨碍这次只读 Investigation/JDBC 验收，未额外部署调度平台。
- 真实模型：本次环境未配置 Provider/Azure 凭据，真实模型端到端 **NOT_RUN**。如要补充，先安全配置已有 `PULSEFLOW_AGENT_MODEL`、Provider API Key、Azure PII endpoint/key 和机器 Token，再通过已有登录后的 `/api/investigations` 或 Web 控制台执行。保留所有 PII 和权限检查，可能产生模型/Azure 费用。`REAL_AGENT_EVAL` 使用 fixture Java 数据，仍不能替代真实 Java 联调。

```powershell
# 无须 Docker daemon 的 Compose 契约检查
python testing/observability/check_config.py

cd pulseflow-agent
uv sync --locked
uv run ruff check .
uv run mypy src tests
uv run pytest -q
cd ../pulseflow
mvn --batch-mode --no-transfer-progress verify

# 真实 MySQL JDBC tracing（需要 Docker）
$env:PULSEFLOW_TEST_DOCKER = 'true'
mvn --batch-mode --no-transfer-progress -pl pulseflow-boot -am `
  '-Dtest=AgentTelemetryTest' '-Dit.test=AgentTelemetryMysqlIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
Remove-Item Env:PULSEFLOW_TEST_DOCKER
```

本次脱敏运行证据见 [本地验收记录](jaeger-local-verification.md)。
