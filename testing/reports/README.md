# 测试报告

运行时报告写入 `testing/reports/<run-id>/`，并由 `testing/.gitignore` 忽略。

- Functional：`functional-report.json`、`functional-report.md`，以及每个场景的 Replay、
  MySQL、Redis、Profile、Campaign、Attribution、Compensation 结果；
- Performance：`performance-report.json` 和 `k6-summary.json`；
- `run-all.ps1`（如果使用）另外生成 `run-all-report.json`，分别记录功能、性能和总状态。

状态只有 `PASS`、`FAIL`、`NOT_RUN`。缺少依赖、调度任务未触发或当前源码不支持某段链路
都必须保留为 `NOT_RUN`。
