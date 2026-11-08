# PulseFlow 业务演示

启动隔离的 MySQL 8、Redis、Kafka、Java 和 XXL-JOB Admin。Flyway 运行到 V11 后，在同一业务时区导入 [种子数据](seed-demo-data.sql)。种子只供本地干净演示库使用。启动控制台后查看 Users、Campaigns、Deliveries 与 Attribution。

1. EVENT 商品浏览实时促销。向 `POST /api/events` 提交 `CONTENT_VIEW`，`properties.category` 使用 `electronics`。Campaign 97001 命中，发送一条 IN_APP。改用其他分类可观察规则不匹配。重复提交同一 `eventId`，观察触达去重。
2. DELAYED 加购提醒。提交带 `properties.cartItemId` 的 `ADD_CART`，等待 Campaign 97002 的 60 秒。用另一商品项试一次，在到期前提交相同用户和商品项的 `REMOVE_CART` 或 `ORDER_PAID`，应取消该项提醒。
3. SCHEDULED 未购召回。依次触发 `dailyMetricJob`、`windowMetricJob`、`campaignSelectionJob`。Campaign 97003 仅匹配用户 1024。1025 已支付、1026 活跃不足、1027 账号禁用。
4. 点击与归因。对已发送任务，用配置的机器凭据上报相同用户的 `CLICK`，属性写入真实 `taskId`。随后上报带稳定 `orderId` 的 `ORDER_PAID`，等待 5 分钟宽限及轮询，在归因和绩效页核对。

Python Agent 通过既有受控接口提交类型化提案，Java 校验和预览。草稿确认保留原归属、幂等及生命周期边界。相关口径和部署见 [主业务改造报告](../marketing-core-convergence.md)。前端用例和回放见 [Testing README](../../testing/README.md)。
