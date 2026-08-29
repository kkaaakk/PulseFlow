# 固定 Fixture

## 中文说明

这里保存少量可以提交到 Git 的固定 Fixture。`smoke-events-v1.jsonl` 用于快速验证
Functional Replay 和 Validator 连通性，旁边的 Manifest 保存精确 checksum 与预期小时指标。
大规模数据和边界场景由固定 seed 在运行时生成，不提交大文件。

`smoke-events-v1.jsonl` 是一个可以提交到 Git 的小型 Fixture，用于快速检查 Functional Replay
和 Validator 连接是否正常。旁边的 Manifest 保存精确 checksum 和预期的 canonical
小时指标总量。

较大的 NORMAL、duplicate、out-of-order、late、invalid、hot-user、concurrency 和 campaign 数据集
会在运行时根据 seed 生成。它们的 JSONL/Manifest 文件位于 `data/generated/`，
并已被 Git 忽略。
