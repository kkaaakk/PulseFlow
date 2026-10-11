# Agent PII 防护契约

Python Agent 在任何内容发给大语言模型前使用 AzurePiiGuardrail.check()。PII（个人身份信息）检测、业务字段防护和 Java 权限/聚合工具边界共同生效，不能互相替代。

## 五类自由文本范围

| 规范化类型 | 来源与判断 |
|---|---|
| NAME（姓名） | Azure Person |
| PHONE（电话） | phonenumbers 有效格式；Azure PhoneNumber 必须经本地确认 |
| EMAIL（邮箱） | Azure Email |
| GOVERNMENT_ID_CN（中国居民身份证） | python-stdnum cn.ric；兼容 Azure 两种身份证标签 |
| BANK_CARD（支持的支付卡） | Presidio CreditCardRecognizer 内置校验；不等于任意银行账户 |

Azure 原始标签、码点跨度和置信度保留为内部证据，再按规范类型合并去重和判断。Organization、PersonType、一般日期、Location、年龄、业务数量、URL 不因存在标签而单独阻断。

用户已将详细地址退出自由文本承诺：Address 可归一化为 POSTAL_ADDRESS 供诊断，但不单独阻断，也不计入五类门槛。**用户直接输入纯地址可能放行**；结构化禁止 address 字段仍拒绝。姓名仍有语义漏检/误报风险，不能宣称覆盖所有个人信息。

## 业务字段和工具边界

结构化输入的禁止键大小写不敏感，包含嵌套对象、数组及 Pydantic 别名：userId、userIds、mobile、phone、email、address、idCard、idNumber、deviceId、imei、rawEvents、orderDetails、behaviourLogs、fullName、realName。即使值为空、Azure 返回 clean 也拒绝。

自然语言中的独立禁止字段名仍默认拒绝。只有完整匹配元数据问题文法的单一概念问题可继续检测，例如问定义、类型、命名或质量计算公式；不能附带值、明细获取、导出要求或其他指令，不依靠“仅供学习”声明豁免。复合概念请求保守拒绝，customerUserIdAlias 不因子串误判。允许问题仍执行 Azure 与本地校验。

Java Tool 保留权限、extra=forbid、聚合字段白名单、警告枚举；维度值限定为已知渠道、日期、活动 ID 和模型，版本值限定为 Java 实际格式。任意字符串不能藏在合法键的值中进入模型，非法值以内容无关的 blocked_tool_value 拒绝。Campaign/Proposal/Draft 的业务流程与写权限不变。

Agent 用户输入、上下文共享可见字段、Tool 紧凑观察及后续内容均保留模型前检查。上下文渲染与检查共用 context_payload()，允许的完整概念问题不会因渲染标签再次被误拒。

## 电话边界和故障隐私

phonenumbers 使用 CN 地区、严格分组及有效号码判断。点分/IP/版本包络、字母数字业务标识及空格连接号内的数字不会直接当作电话；独立完整 Contact、Tel、Call 和中文联系标签可以确认联系语义，业务词尾片段不能冒充标签。Unicode 空白/横线、大小写和换行有固定回归。完全同形的联系标签与业务码仍有歧义，优先保护明确联系方式，不承诺所有国际格式、混淆字符或分机。

Fail-closed（故障时拒绝放行）：无凭据、超时、401/403、429、服务错误、畸形响应、无效 Unicode 跨度或本地校验器异常均拒绝；不能将错误当作无敏感信息。

Azure REST API 2024-11-01、zh-hans、原5秒超时；loggingOptOut=true、stringIndexType=UnicodeCodePoint。Azure 请求抑制共享 HTTP 客户端遥测，Key、资源地址、原文、匹配值不进入错误、日志或 trace。真实模型配置启动时校验 Azure 凭据，测试模型不会冒充云检测。

## 验证与应用

完整 Python 回归包含真实 check() 的模拟 Azure/模型测试、原始 IP/v3/v4 固定向量及最终100条保存响应的离线重放；不会读取真实凭据或收费调用。最终合成批次的历史真实验收为100成功、电话40/40、编号误拦0/40；这不是生产全类型安全保证。

保留 main 的 Jaeger/OTLP 可观测性测试。集成范围与验证记录见 [main 集成说明](../security/azure-pii-main-integration.md)。合并不等于部署：应用更新后执行 uv sync --locked 并按运行环境安排 Agent 重启；不要覆盖现有 .env。
