# PulseFlow Fullstack V1 Acceptance Report

验收日期：2026-08-30（Asia/Shanghai）

## 1. Acceptance Result

```text
FULLSTACK_V1_ACCEPTANCE: COMPLETE
PulseFlow Fullstack V1: COMPLETE
```

本地验收以最终代码为准：真实 Browser → Vue（`VITE_DEMO_MODE=false`）→ Spring Boot → MySQL / Redis / Kafka 闭环通过；Docker-gated IT、前端回归、确定性 replay 和 k6 smoke 均通过。

## 2. Git 与本地固化

- Base SHA：`fa5d0dc9b7426b15e0a93e9c3860183504e7cc9d`
- Local branch：`codex/fullstack-local-v1`
- Final local SHA（Fullstack 实现 commit）：`0267691350793f25219b2c2c36b17d56738f0e21`
- Local commit：`0267691 feat: complete PulseFlow fullstack console v1`
- 本次已成功创建目标分支并写入本地 Git。
- 未执行 reset、回退、删除 `.git`、PR 或远程 CI 操作；本地验收报告生成时尚未进行 GitHub Delivery。

原有 docs、testing、Maven 配置和任务提示文件保持未暂存、未提交；本次只提交了可确认属于 Fullstack V1 的代码、测试、前端、README 和验收报告。

## 3. 变更归属

- Pre-existing user changes：Phase 0 已存在的 docs、AI/Boot/Event 配置与测试修改均保留。
- Fullstack changes：`pulseflow-web/`、Boot Web Query/Auth、AI refresh-preview 接入、相关测试、README 和本报告。
- Acceptance hardening：事件时间戳解析、可选 `targetId` 的 NULL 语义、Realtime Redis `StringCodec` 读取、本地 Sa-Token memory session 配置、fixture manifest 样例修正及对应测试。
- Unknown / ambiguous：包含用户原修改和本轮修改的重叠文件未强行拆分，未提交。

没有覆盖原有的 `FULLSTACK_LOCAL_REPORT.md`。

## 4. Demo Mode OFF

- 使用 `VITE_DEMO_MODE=false` 启动 `pulseflow-web` 5175 端口。
- Login、Dashboard、Copilot 和所有查询页面均通过真实 `/api` 请求渲染。
- Real Backend Playwright 1/1 通过；未发生 Demo fixtures fallback。

## 5. Runtime Infrastructure

复用 `testing/docker-compose.test.yml`，最终状态：

| Service | Image | Status | Port |
|---|---|---|---:|
| MySQL | `mysql:8.0` | healthy | 13306 |
| Redis | `redis:7.2-alpine` | healthy | 16379 |
| Kafka | `apache/kafka:3.7.0` | healthy | 19092 |

Spring Boot 使用测试端口连接上述容器，`GET /actuator/health` 返回 HTTP 200 / `{"status":"UP"}`。AI 使用 Fake AI，仍经过现有 PII、Parser、DSL Validator、Audience Preview、ownership 和确认流程。XXL-JOB admin 未单独启动，executor 注册会产生 warning，但不影响本地 V1 业务链路。

## 6. Real Browser → Backend Flow

Real Backend E2E：

```text
Login
→ AI Copilot
→ POST /api/ai/campaigns/parse
→ Rule Builder 修改
→ PUT draft
→ refresh-preview
→ 选择内容
→ Confirm Campaign
→ Campaign Detail
→ Users
→ User 360 / Event Timeline
```

结果：`npm run test:e2e:integration` — **1 passed**。

## 7. AI Draft 与持久化证据

最终测试库中的真实记录包括：

- `campaign_ai_draft`：draft `6` 为 `CONFIRMED`，`operator_id=1024`，`confirmed_campaign_id=5`。
- `campaign`：campaign `5` 已真实入库，`created_by=1024`，channel 为 `IN_APP`。
- `campaign_rule`：campaign `5` 真实存在 `3` 条规则。
- 真实数据库查询结果验证了 `draftId → confirmedCampaignId` 对应关系。

## 8. refresh-preview A/B

真实 REST 验证结果：

- draft `8` 初始 Preview A：audience `3`，data version `profile-20260830-1556`。
- 修改第一条规则值为 `7`，PUT 返回 code `200`。
- refresh-preview 后 Preview B：audience `1`，第一条规则值为 `7`，warnings `0`。
- A/B 人群数发生语义变化，说明重新执行了 Java Audience Preview；相同分钟内 data version 保持同一 snapshot 版本是预期行为。

## 9. Auth、Ownership 与安全

- 未登录访问 `/api/dashboard/summary`：HTTP `401`。
- Operator `1` 尝试确认 Operator `1024` 创建的 draft `8`：HTTP `403`，拒绝原因为不拥有该 draft。
- Operator `1` 尝试读取 Operator `1024` 创建的 campaign `5` review：HTTP `403`。
- operatorId 继续来自 Sa-Token context，不信任 request body。
- DSL Validator、promotion facts 和 campaign ownership 仍由后端决定。
- 前端未包含 AI key、Azure key、数据库配置或密码。

## 10. Redis Fallback

真实 User 360 验证通过：

- Redis 正常：HTTP `200`，`realtimeSource=REDIS`，`realtimeAvailable=true`。
- 停止 Redis：已登录会话仍可访问 User 360，HTTP `200`，`realtimeSource=MYSQL_FALLBACK`，`realtimeAvailable=false`；今日浏览/搜索等可由 MySQL 事件计算，未伪造 Redis-only 数值。
- Redis 已在 finally 路径恢复并重新报告 healthy。

为使单节点本地 auth session 不阻塞业务降级，新增可配置的 Sa-Token memory session store，默认本地模式使用 memory；需要分布式会话时设置 `PULSEFLOW_AUTH_SESSION_STORE=redis`。

## 11. Docker Integration Tests

最终执行：

```powershell
$env:PULSEFLOW_TEST_DOCKER="true"
$env:DOCKER_HOST="npipe:////./pipe/dockerDesktopLinuxEngine"
cd pulseflow
mvn clean verify
```

结果：**BUILD SUCCESS**，无 Failures、无 Errors。

Docker-gated 测试真实执行：

- Flyway migration IT：`2` tests passed。
- AI enabled/disabled bootstrap checks：`10` tests passed。
- Event idempotent consumption IT：`3` tests passed。
- 最终 Boot 单元测试：`18` tests passed；相关模块单元测试也全部通过。

没有扩大 skip 条件、删除 assertion 或把 IT 改成 mock。

## 12. Maven 与 Backend Tests

- `mvn clean verify`：PASS。
- campaign unit tests：`11` passed。
- event unit tests：`3` passed。
- AI unit/integration tests：`89` passed。
- Boot unit tests：`18` passed。
- Event persistence 受影响测试覆盖 fractional timestamp、minute timestamp 和 NULL `targetId`。
- Realtime profile 受影响测试覆盖 fractional timestamp 和 minute precision。

## 13. Frontend Regression

工作目录：`pulseflow-web/`

- `npm run typecheck`：PASS。
- `npm run lint`：PASS。
- `npm run test`：PASS，2 files / 5 tests。
- `npm run build`：PASS；仅有 ECharts/Element Plus bundle size warning，无构建错误。
- `npm run test:e2e:demo`：PASS，2/2。
- `npm run test:e2e:integration`：PASS，1/1。

## 14. Replay 与 k6

- `python testing/functional/verify_contract.py`：PASS，14/14。
- fixture replay smoke：PASS，10/10 HTTP requests，0 failures。
- fixture validator：PASS，13/13 checks，MySQL canonical samples、hourly metrics、Redis processed TTL、realtime profile 和 daily values 均通过。
- k6 smoke：PASS，1 VU / 5s，48 requests，API failures `0/48`，HTTP failures `0/48`，p95 `5.91ms`。
- 未执行长时间 load/stress。

## 15. Web Query API 与前端页面

已接入真实查询层：

- Dashboard：summary、trends。
- Campaign：list、detail、performance、delivery trend、deliveries、attribution、review。
- User：list、User 360、profile/events 聚合。
- Events、Deliveries、Attribution、System。
- Auth：login、logout、me。

真实 Browser 人工巡检页面：Dashboard、AI Copilot、Campaign List、Campaign Detail、Users、User 360、Events、Deliveries、Attribution、System。

## 16. Visual QA

使用真实模式 Browser 检查：

- 1440×1024：全部 10 个页面无 horizontal overflow、无 console error/warn、无明显 error 文案。
- 1024×800：全部 10 个页面无 horizontal overflow、无 console error/warn、无明显 error 文案。
- Dashboard 日期使用当前日期 `2026/08/30`，未保留旧的固定演示日期。
- Dashboard “需要关注”区域只呈现待处理/告警提醒，没有混入“已通过”。
- Copilot 保留自然语言 → DSL → Preview → Insight/Content → Confirm 的业务结构。

视觉验收截图仅保存在本地临时 visualization 目录，没有进入正式仓库；页面结构、响应式尺寸、overflow 和 console 结果均已通过 Browser 检查复现。

## 17. Known Limitations / Deferred

- 当前工作区仍有原有的 docs、testing、Maven 配置和任务提示文件未提交；Fullstack V1 已安全固化在本地分支及实现 commit `0267691350793f25219b2c2c36b17d56738f0e21`。
- 本轮只执行 k6 smoke，没有执行长时 load/stress。
- ECharts/Element Plus 生产 bundle 有大 chunk warning，未影响 build 或运行；后续可做独立性能优化。
- 本地测试数据库/Redis 中保留了本轮验收数据，属于 test compose volume 数据。

Remaining blockers：**none for local Fullstack V1 acceptance**。

## 18. Local-only Boundary

```text
Remote Push (local acceptance phase): NOT PERFORMED
Pull Request: NOT CREATED
GitHub Actions: NOT USED
Cloud Deployment: NOT PERFORMED
Release: NOT PERFORMED
```
