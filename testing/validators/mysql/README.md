# MySQL 校验

## 中文说明

MySQL 校验器会读取 Flyway 实际创建的表，比较 `user_event` 唯一事件数和
`user_metric_hourly` 指标总量，并检查 21 张表及 Campaign/Attribution 事实。
校验器本身只读；只有 `prepare-campaign-fixture.sql` 会写入一次性测试数据，且
必须通过测试环境保护。

`../validate_run.py` 是可执行的 Validator。它查询 Flyway 实际创建的表，将 canonical
`user_event` 行和 `user_metric_hourly` 总量与数据集 Manifest 比较；同时检查 21 张
表的 Schema，并在 Campaign Fixture 场景下检查 Delivery Task 和 Attribution 事实。

Validator 只读。除非设置 `PULSEFLOW_TEST_ENV=test`，否则会拒绝非测试数据库；默认
目标是隔离 Compose 环境中的 `127.0.0.1:13306/pulseflow_test`。

`prepare-campaign-fixture.sql` 是唯一的可选准备脚本。Replay
`campaign-frequency-attribution-v1` 前，只能对一次性测试数据库执行它；PowerShell
包装脚本会执行同样的回环地址和测试环境保护。
