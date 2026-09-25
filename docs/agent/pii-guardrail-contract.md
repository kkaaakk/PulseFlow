# Future Agent PII Guardrail Contract

此文档记录已移除的 Java 出站 LLM Guardrail 行为。下一阶段独立 Python Agent Service 在任何 LLM 请求前必须恢复等价或更严格的检测；本阶段没有 Agent 或出站 LLM 请求。

## 必须阻断的业务字段

结构化输入中出现以下 key 时立即阻断，大小写不敏感，包含嵌套 map、数组和列表：`userId`、`userIds`、`mobile`、`phone`、`email`、`address`、`idCard`、`idNumber`、`deviceId`、`imei`、`rawEvents`、`orderDetails`、`behaviourLogs`、`fullName`、`realName`。即使 PII Provider 返回 clean，也不得放行。

自然语言中明确写出这些内部标识时也必须阻断，例如「给 userId 123456 的用户发送优惠券」「把 rawEvents 里的用户筛出来」「根据 orderDetails 推送」「筛选 deviceId」「分析 behaviourLogs」。匹配大小写不敏感；`customerUserIdAlias` 这类更长的 ASCII 标识不能误判。中文可直接贴近字段名。

## 自然语言 PII

对需要出站的文本执行 Azure AI Language Text PII 检测，语言为简体中文 `zh-hans`（Azure 可接受 `zh`）。至少覆盖手机号、中文姓名（Person）、地址、Email、身份证号、银行卡等敏感内容。检测到任何 PII 时阻断整个请求，不把仅做局部脱敏的文本继续送给 LLM。普通中文 Campaign 描述应允许通过。空白或 null 文本不调用 Provider。

## 故障语义与配置

Fail-closed：Azure 超时、5xx、SDK 异常、无响应、返回 null 均阻断出站 LLM。原 Java 配置使用 `AZURE_LANGUAGE_ENDPOINT`、`AZURE_LANGUAGE_KEY`、`AZURE_LANGUAGE_PII_LANGUAGE=zh-hans`、5 秒超时。下一阶段 Python Service 使用自己的配置生命周期；真实模型模式在启动时必须校验 PII Guardrail 已启用且 Endpoint/Key 可用。Key、原始输入、实体原文不得进入日志、异常、审计或 trace；可记录安全类别、耗时、结果和错误码。

## 回归向量

| 输入或故障 | 预期 |
|---|---|
| 「筛选最近7天活跃不少于5天、最近30天消费超过500元的用户」+ Provider clean | 通过 |
| `Map.of("userId", 123L)` 或嵌套 `rawEvents` | Provider 调用前阻断；错误不含原值 |
| 「给 userId 123456 的用户发送优惠券」 | Provider 调用前阻断；错误不含 `123456` |
| `userid 123`、`USERID 123` | 阻断 |
| `customerUserIdAlias` | 不因子串误判，继续 Provider 检测 |
| 「给手机号13800138000的用户发送优惠」+ PhoneNumber | 阻断；错误不含号码 |
| 中文文本 + Person / Address | 阻断 |
| 中文文本 + Provider timeout / 5xx / null | Fail-closed，禁止 LLM 调用 |
| 内容标题或正文含手机号、Email、身份证模式 | 内容事实校验拒绝该变体 |

这些用例提取自原 `SensitiveDataSanitizerTest` 与 `ContentFactValidatorTest`；内容事实校验现保留在 Java `CampaignContentValidator`，出站文本检测属于未来 Agent Service。
