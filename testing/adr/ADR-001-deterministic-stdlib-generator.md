# ADR-001：使用基于标准库的确定性数据集生成器

> 中文说明：本决策确定使用 Python 标准库实现测试数据生成器。通过固定随机种子、
> 固定基准时间、稳定 JSON 序列化和 SHA-256，使同一配置可以反复生成可比较的数据，
> 同时避免为测试工具额外引入 Datafaker/Maven 依赖。

## 状态

已接受

## 背景

第一阶段需要在 Windows 优先的仓库中反复生成 NORMAL、duplicate、out-of-order、late、
invalid、hot-user 以及 campaign/attribution 数据集。应用本身使用 Java，但生成器只是
测试工具，不需要应用的 classpath。引入 Datafaker 和第二套 Maven 构建流程，会给一个
只需要稳定 JSONL 的工具增加依赖下载和运行负担。

## 决策

使用 Python 3.10+ 标准库，并明确指定随机 seed、固定基准时间、规范化 JSON 序列化和
SHA-256 checksum。生成数据不进入 Git，只提交 Fixture、Manifest/配置和生成器本身。

## 影响

### 正面影响

- 不增加 Maven 模块或运行时依赖；
- 可以直接在现有主机的 PowerShell 中运行；
- 相同 seed 和 scale 会产生字节级可比较的输出；
- 大数据集按需生成，不会膨胀 Git 仓库。

### 负面影响

- 生成器不会在编译期复用 Java DTO 类；
- 开发者机器必须安装 Python；
- 契约漂移只能由 `verify_contract.py` 缓解，不能完全消除。

## 备选方案

- **Java + Datafaker**：项目团队更熟悉，但会为测试数据生成增加独立 Maven 生命周期
  和依赖解析；
- **手工编写大 Fixture**：不可扩展，也不适合 Load/Replay 测试，因此不采用。
