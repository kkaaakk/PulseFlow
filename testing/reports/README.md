# 验收报告

## 中文说明

运行报告保存在 `testing/reports/<run-id>/`。每份报告应包含数据集和 checksum、
Replay 结果、MySQL/Redis 校验、k6 结果（或明确的 `NOT_RUN`）以及失败证据。
报告目录是运行时产物，不提交到 Git。

运行时报告写入 `testing/reports/<run-id>/`，并被 Git 忽略。每份报告必须保留数据集、
Manifest checksum、Replay 结果、MySQL/Redis 检查、k6 summary（或明确的 `NOT_RUN`）
以及失败证据。
