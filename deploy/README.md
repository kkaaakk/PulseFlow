# 完整 Docker 运行栈

项目容器不会随 Docker Desktop 或电脑重启自动启动；需要使用时手动运行 `./start.ps1`。该设置不影响 Docker Desktop 自身的开机启动。

从仓库根目录运行 `./start.ps1`。首次启动自动创建被 Git 忽略的根 `.env`，导入 `pulseflow-agent/.env` 中已有的模型和 Azure PII 配置，并生成内部令牌及数据库密码；后续启动保留这些值。默认复用已有镜像，缺少镜像时自动构建；修改源码后使用 `./start.ps1 -Build` 重建。也可先运行 `./start.ps1 -ConfigureOnly` 准备配置，再运行 `docker compose up --build -d --wait`。

默认运行 DeepSeek，Agent 启用 production 配置校验。模型标识为 `deepseek:deepseek-flash`，需要 `PULSEFLOW_AGENT_API_KEY`、`AZURE_LANGUAGE_ENDPOINT`、`AZURE_LANGUAGE_KEY`；`PULSEFLOW_AGENT_BASE_URL` 使用 `https://api.deepseek.com`。旧的 `openai:deepseek-*` 配置会自动迁移，其他模型服务不再接受。缺配置或选择 `test` 模型时明确失败。离线测试使用 `testing/docker-compose.test.yml`，普通 CI 不调用付费模型或 Azure。

| 服务 | 本机入口 | 默认能力 |
|---|---|---|
| Web | http://127.0.0.1:5173 | 真实 API、SSE 代理、刷新路由支持 |
| Java | http://127.0.0.1:8080 | Redis 登录会话、内部 Tool API、Agent 网关、已鉴权模拟事件接口 |
| XXL-JOB | http://127.0.0.1:8081/xxl-job-admin | 自动登记并开启 10 个业务任务 |
| Jaeger | http://127.0.0.1:16687 | Java 和 Python OTLP Trace 查询 |
| 业务 MySQL | 127.0.0.1:13307 / pulseflow | Flyway 自动迁移 |
| Redis | 127.0.0.1:16380 | 会话、实时画像、频控、延迟队列；AOF 持久化 |
| Kafka | 127.0.0.1:19093 | 事件及触达消息 |

Agent MySQL 和 XXL-JOB MySQL 各使用独立用户及 Docker 卷，不发布宿主端口。Python 使用单个 worker；Java 执行器宣告容器地址 `http://java:9999/`，调度中心不通过宿主机回调。所有公开入口绑定本机回环地址。

控制台账号：Operator ID `1024`，默认密码 `pulseflow-local`；管理员 ID `1`，默认密码 `pulseflow-admin`。可在根 `.env` 修改 `PULSEFLOW_OPERATOR_PASSWORD` 和 `PULSEFLOW_ADMIN_PASSWORD`。XXL-JOB 用户名 `admin`，随机生成的密码保存在 `.env` 的 `XXL_JOB_ADMIN_PASSWORD`。

## 默认调度

统一使用 Asia/Shanghai 时区。一次性 `xxl-job-bootstrap` 容器在 Java 和调度中心健康后执行登记；首次创建时启用调度，重复运行不会添加同名任务，也不会覆盖手工调整的周期和暂停状态。

| Handler | 周期 |
|---|---|
| dailyMetricJob | 每小时整点，聚合已结束的小时桶 |
| windowMetricJob | 每 5 分钟 |
| tagRecalcJob | 每 5 分钟，偏移 2 分钟 |
| campaignSelectionJob | 每 10 秒 |
| campaignPerformanceSummaryJob | 每 5 分钟，偏移 1 分钟 |
| dispatchRetryJob | 每 15 秒 |
| compensationJob | 每 30 秒，偏移 5 秒 |
| retryCompensationJob | 每 30 秒，偏移 10 秒 |
| delayTaskRecoveryJob | 每分钟第 20 秒 |
| dataCleanupJob | 每天 03:30 |

清理任务按现有实现保留 30 天内的已结束补偿记录及 90 天内的归因任务、点击、触达记录和标签历史。Campaign 仍需处于 ACTIVE 才参与触发；人工确认方案创建的 Campaign 仍遵守 DRAFT 状态契约。延迟任务执行与归因消费使用应用自己的 Spring 定时器。

调度中心及数据库结构固定为与 Java 客户端一致的 2.4.1；`xxl-job/schema-2.4.1.sql` 来自 [XXL-JOB 官方建表脚本](https://github.com/xuxueli/xxl-job/blob/2.4.1/doc/db/tables_xxl_job.sql)。Jaeger 固定为 2.17.0，按 [官方 all-in-one 方式](https://www.jaegertracing.io/docs/2.17/getting-started/) 接收 OTLP，当前 Trace 使用内存存储，重启 Jaeger 会清空历史 Trace。Prompt、工具正文及敏感字段仍由已有遥测过滤器排除。

## 日常命令

```powershell
docker compose ps --all
docker compose logs --tail 100 java agent xxl-job-bootstrap
docker compose up -d --wait
docker compose down
```

验证真实 HTTP、Redis 会话、任务登记和 Trace 导出：`python deploy/verify.py`。加 `--trigger-jobs` 可逐个触发并核对 10 个任务的成功回执（包含保留期清理）；加 `--real-agent` 可执行一次有预算限制的真实模型/Azure 调查并核对 Java 证据和 Agent Trace。

`down` 保留数据库和日志卷。根目录 `.env` 包含这些卷对应的登录凭据，后续启动需保留。构建上下文已排除本地密钥、依赖目录和运行产物。前端模拟数据、开发免密登录、付费真实模型评测保持显式开发或验证选项。
