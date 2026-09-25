# PulseFlow 当前业务演示

本演示验证 Java 确定性链路：事件接入 → 画像 → Campaign 选择 → 频控 → Delivery → 点击归因 → Campaign Performance Summary。旧自然语言 Copilot 接口已移除；Pydantic AI 尚未接入。

## 准备

启动 MySQL 8、Kafka、Redis 和 XXL-JOB Admin，然后在 `pulseflow/` 运行 `mvn clean verify` 与 `mvn spring-boot:run -pl pulseflow-boot -am`。Flyway 自动执行 V1–V5；演示数据可参考 [seed-demo-data.sql](seed-demo-data.sql)。

## 控制台

在 `pulseflow-web/` 运行 `npm ci`、`npm run dev`。登录后依次查看 Dashboard、Events、Users、Campaigns、Deliveries、Attribution 和 System。Campaign Detail 显示规则、人群、触达和归因；绩效汇总由 `CampaignPerformanceSummaryJob` 计算。

## 草稿与确认

`POST /api/campaign-drafts` 接受类型化 `CampaignDsl`；`GET /api/campaign-drafts/{id}` 查询当前操作员拥有的草稿；`PUT /api/campaign-drafts/{id}` 重新校验编辑后的 DSL；`POST /api/campaign-drafts/{id}/refresh-preview` 刷新预估；`POST /api/campaign-drafts/{id}/confirm` 幂等创建正式 Campaign。所有调用需要 Sa-Token 登录，服务端以会话中的操作员 ID 校验归属。当前控制台没有草稿创建 UI，未来 Agent Service 只会提交类型化 Proposal，人工确认仍由 Java 控制。

## 回归

前端运行 `npm run typecheck`、`npm run test`、`npm run build`、`npm run test:e2e:demo`。真实后端 E2E 需要先启动依赖与 Boot，再运行 `npm run test:e2e:integration`。专项业务 Replay 与性能测试见 [Testing README](../../testing/README.md)。
