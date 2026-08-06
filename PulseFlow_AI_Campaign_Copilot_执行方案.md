# PulseFlow AI Campaign Copilot 执行方案

> 适用对象：Trae / Codex 等代码代理  
> 项目定位：事件驱动用户运营平台（CDP + Campaign）  
> 本次目标：在不削弱 Kafka、Redis、XXL-JOB、用户画像、规则引擎、触达归因主线的前提下，增加一个完整但不过度膨胀的 AI Campaign Copilot。

---

## 1. 改造目标

PulseFlow 当前核心链路应继续保持为：

```text
用户行为事件
→ Kafka
→ 实时指标与窗口指标
→ 用户画像与标签
→ Campaign 规则判断
→ 频控与幂等
→ 消息触达
→ 点击与转化归因
```

本次 AI 改造不是增加一个通用聊天机器人，也不是让大模型接管业务执行，而是在 Campaign 生命周期中增加四项辅助能力：

1. **AI 创建 Campaign**：自然语言转换为受约束的 Campaign DSL。
2. **AI 人群洞察**：解释目标人群的聚合画像特征。
3. **AI 策略与文案建议**：根据活动目标、人群特征和真实优惠信息生成策略及文案。
4. **AI 活动复盘**：基于后端计算出的活动指标生成结构化总结。

最终形成：

```text
创建前：理解运营意图
创建中：解释人群并辅助生成内容
执行时：仍由确定性 Campaign 引擎负责
结束后：解释活动结果并提出下一步建议
```

---

## 2. 范围控制

### 2.1 本次必须实现

- 自然语言生成 Campaign DSL
- DSL 字段白名单、类型、操作符和业务约束校验
- AI 草稿保存
- 人群数量预估
- 目标人群聚合指标
- AI 人群洞察
- AI 生成三个差异化营销文案
- 文案长度和事实一致性校验
- Campaign 执行结果聚合
- AI 活动复盘
- AI 调用基础审计
- Mock AI Provider 和自动化测试

### 2.2 本次明确不实现

- 通用 AI 聊天助手
- 多 Agent
- 自主 Agent Loop
- AI 自动执行 SQL
- AI 自动创建并发送 Campaign
- AI 自动修改线上活动
- 向量数据库
- Campaign RAG
- 自建 Prompt 管理平台
- 多模型管理后台
- 在线机器学习
- 用户转化率预测模型
- 流失预测模型
- 推荐算法训练平台
- 自动 A/B 测试优化
- 实时动态调价或优惠决策

这些能力可以作为后续扩展，不纳入本轮交付范围。

---

## 3. 核心设计原则

### 3.1 AI 负责理解与解释，Java 系统负责执行

大模型可以：

- 把自然语言转换为结构化规则
- 解释聚合指标
- 生成策略建议
- 生成营销文案
- 总结活动结果

大模型不能：

- 直接执行 SQL
- 直接访问任意数据库表
- 直接修改用户画像
- 直接创建正式 Campaign
- 直接触发消息发送
- 绕过规则校验
- 绕过频控和幂等
- 使用系统中不存在的字段
- 虚构优惠、库存、活动期限或业务数据

### 3.2 所有核心 AI 输出必须结构化

以下功能必须返回 JSON：

- Campaign DSL
- 人群洞察
- 策略建议
- 文案列表
- 活动复盘

业务代码不能依赖自然语言正则解析。

### 3.3 原有 Campaign 引擎是唯一执行入口

AI 生成的 DSL 必须经过：

```text
JSON 解析
→ 字段白名单校验
→ 字段类型校验
→ 操作符校验
→ 业务边界校验
→ 转换为原有 Campaign Rule
→ 人群预估
→ 人工确认
→ 调用原有 Campaign 创建服务
```

### 3.4 不向模型发送用户级原始数据

允许发送：

- 目标人群数量
- 聚合指标
- 标签占比
- 品类占比
- 历史平均值
- 活动结果指标

禁止发送：

- 用户姓名
- 手机号
- 地址
- 身份证信息
- 用户完整行为轨迹
- 用户级订单详情
- 设备唯一标识
- 未脱敏的运营数据

---

## 4. 建议模块结构

现有模块预计包括：

```text
pulseflow-common
pulseflow-event
pulseflow-profile
pulseflow-campaign
pulseflow-job
pulseflow-simulator
pulseflow-boot
```

新增模块：

```text
pulseflow-ai
```

建议目录：

```text
pulseflow-ai
└── src/main/java/<base-package>/ai
    ├── api
    │   ├── AiCampaignController.java
    │   ├── AiInsightController.java
    │   └── AiReviewController.java
    │
    ├── application
    │   ├── CampaignIntentService.java
    │   ├── CampaignAiDraftService.java
    │   ├── AudienceInsightService.java
    │   ├── CampaignContentService.java
    │   └── CampaignReviewService.java
    │
    ├── domain
    │   ├── campaign
    │   ├── insight
    │   ├── content
    │   └── review
    │
    ├── provider
    │   ├── AiModelClient.java
    │   ├── AiRequest.java
    │   ├── AiResponse.java
    │   ├── OpenAiCompatibleClient.java
    │   └── FakeAiModelClient.java
    │
    ├── prompt
    │   ├── CampaignIntentPromptBuilder.java
    │   ├── AudienceInsightPromptBuilder.java
    │   ├── CampaignContentPromptBuilder.java
    │   └── CampaignReviewPromptBuilder.java
    │
    ├── guardrail
    │   ├── CampaignDslValidator.java
    │   ├── AiFieldRegistry.java
    │   ├── AiOutputParser.java
    │   ├── ContentFactValidator.java
    │   └── SensitiveDataSanitizer.java
    │
    ├── infrastructure
    │   ├── config
    │   ├── persistence
    │   └── observability
    │
    └── support
        ├── AiTaskType.java
        ├── AiErrorCode.java
        └── AiFeatureProperties.java
```

### 模块依赖原则

建议：

```text
pulseflow-ai
→ 依赖 pulseflow-common
→ 依赖 pulseflow-profile 的查询接口
→ 依赖 pulseflow-campaign 的应用层接口
```

禁止：

```text
pulseflow-campaign
→ 反向依赖 pulseflow-ai
```

AI 模块应当是可选增强模块，关闭后不影响原有系统运行。

---

## 5. 第一阶段：项目现状检查

Trae 在改代码前必须先检查现有项目，不得直接假设类名和数据结构。

### 5.1 必查内容

1. 父级 `pom.xml` 和模块结构
2. Spring Boot、Java、MyBatis/MyBatis-Plus 版本
3. Campaign 规则实体和规则执行入口
4. 用户画像字段定义位置
5. Redis 画像 Key 结构
6. MySQL 画像或快照表结构
7. Campaign 创建服务
8. 人群查询或人群预估逻辑
9. Campaign 状态机
10. 消息触达和频控入口
11. 点击、转化归因事件结构
12. XXL-JOB 任务组织方式
13. 全局异常处理方式
14. 日志、Trace ID 和统一响应结构
15. 数据库迁移方式
16. 当前测试分层和测试命令

### 5.2 阶段输出

生成：

```text
docs/ai-current-architecture-analysis.md
```

至少包含：

- 可复用的现有类
- 需要新增的类
- 需要修改的类
- 模块依赖图
- 数据流图
- 预计风险
- 实际文件路径
- 实施顺序

在该文档完成前，不进行大规模编码。

---

## 6. AI Provider 基础设施

### 6.1 抽象接口

定义统一客户端：

```java
public interface AiModelClient {

    AiResponse generateStructured(AiRequest request);

    default boolean isAvailable() {
        return true;
    }
}
```

建议 `AiRequest` 包含：

```text
requestId
taskType
systemPrompt
userPrompt
responseSchemaName
temperature
maxTokens
metadata
```

建议 `AiResponse` 包含：

```text
requestId
provider
model
rawContent
structuredContent
promptTokens
completionTokens
totalTokens
latencyMs
finishReason
```

### 6.2 第一版实现

实现：

```text
OpenAiCompatibleClient
FakeAiModelClient
```

`OpenAiCompatibleClient` 用于兼容 DeepSeek 或其他 OpenAI 协议模型。

`FakeAiModelClient` 用于：

- 单元测试
- 集成测试
- 本地无 Key 演示
- CI 环境

### 6.3 配置

```yaml
pulseflow:
  ai:
    enabled: false
    provider: openai-compatible
    base-url: ${PULSEFLOW_AI_BASE_URL:}
    api-key: ${PULSEFLOW_AI_API_KEY:}
    model: ${PULSEFLOW_AI_MODEL:}
    timeout-seconds: 30
    max-retries: 1
    mock-enabled: true
```

要求：

- API Key 只能来自环境变量
- 禁止将 Key 写入 Git
- `enabled=false` 时系统正常启动
- AI 接口返回明确的功能关闭错误
- AI 失败不能阻塞原 Campaign 功能

### 6.4 重试策略

只允许：

- 网络超时重试一次
- 5xx 重试一次
- 限流错误不无限重试
- JSON 解析失败不盲目重复三到五次

避免造成重复成本和长时间阻塞。

---

## 7. 功能一：AI 创建 Campaign

这是本轮最核心的 AI 能力。

### 7.1 用户输入示例

```text
筛选最近7天活跃不少于5天、最近30天消费超过500元，
并且最近3天没有购买的用户。
今晚8点通过站内信发送满300减30优惠，
每个用户24小时内最多触达一次。
```

### 7.2 Campaign DSL 建议结构

```json
{
  "schemaVersion": 1,
  "campaignName": "高活跃未购买用户召回",
  "objective": "CONVERSION",
  "audience": {
    "logic": "AND",
    "conditions": [
      {
        "field": "activeDays7d",
        "operator": "GTE",
        "value": 5,
        "valueType": "INTEGER"
      },
      {
        "field": "spend30d",
        "operator": "GTE",
        "value": 500,
        "valueType": "DECIMAL"
      },
      {
        "field": "daysSinceLastPurchase",
        "operator": "GTE",
        "value": 3,
        "valueType": "INTEGER"
      }
    ]
  },
  "channel": "IN_APP",
  "schedule": {
    "type": "ONCE",
    "sendAt": "2026-08-03T20:00:00+08:00",
    "timezone": "Asia/Shanghai"
  },
  "frequencyCap": {
    "maxTimes": 1,
    "windowHours": 24
  },
  "promotionFacts": [
    {
      "type": "FULL_REDUCTION",
      "threshold": 300,
      "discount": 30
    }
  ],
  "missingFields": [],
  "warnings": []
}
```

### 7.3 DSL 范围

第一版支持：

- `AND`
- `OR`
- 最多两层规则
- 最多 10 个条件
- 单次发送
- 站内信或项目已有渠道
- 基础频控
- 已有的 Campaign 目标枚举

不要第一版支持：

- 任意深度规则树
- 任意 Cron
- 自定义脚本
- SQL 表达式
- 动态函数
- 跨表自由查询

### 7.4 AI 字段注册中心

实现 `AiFieldRegistry`，从单一来源维护 AI 可用字段。

字段元数据至少包括：

```text
fieldCode
displayName
description
valueType
allowedOperators
minimum
maximum
enumValues
sourceType
enabled
```

示例：

```text
fieldCode: activeDays7d
displayName: 最近7天活跃天数
valueType: INTEGER
allowedOperators: EQ, GT, GTE, LT, LTE
minimum: 0
maximum: 7
sourceType: WINDOW_PROFILE
```

第一版建议开放：

```text
todayViews
cartItemCount
searchCount1h
activeDays7d
viewCount7d
spend30d
orderCount30d
daysSinceLastPurchase
registrationDays
memberLevel
preferredCategory
HIGH_VALUE
PRICE_SENSITIVE
CHURN_RISK
```

字段清单必须由代码动态传入 Prompt，不能在 Prompt 和 Java 中各维护一套。

### 7.5 校验流程

AI 返回后依次执行：

```text
JSON 解析
→ 必填字段校验
→ 未知字段校验
→ 字段类型校验
→ 操作符校验
→ 数值范围校验
→ 枚举校验
→ 时间校验
→ 频控校验
→ 规则复杂度校验
→ 转换为现有 Campaign Rule
```

关键限制：

- `activeDays7d` 不得大于 7
- 金额不得为负数
- `daysSinceLastPurchase` 不得为负数
- 发送时间不得早于当前时间
- 必须包含时区
- 频控次数必须大于 0
- AI 不得创造不存在的渠道
- AI 不得创造不存在的标签
- AI 不得生成 SQL

### 7.6 模糊信息处理

例如用户输入：

```text
晚上给高价值用户发一条优惠提醒
```

模型不得自行决定具体时间和优惠。

应返回：

```json
{
  "status": "NEEDS_CONFIRMATION",
  "missingFields": [
    "schedule.sendAt",
    "promotionFacts"
  ],
  "questions": [
    "请确认具体发送时间",
    "请提供真实优惠信息"
  ]
}
```

### 7.7 人群预估

DSL 校验通过后，调用原 Campaign 规则引擎或画像查询能力。

接口建议：

```java
public interface AudiencePreviewService {

    AudiencePreviewResult preview(AudienceRule rule);
}
```

返回：

```text
estimatedCount
calculatedAt
dataVersion
calculationMode
warnings
```

示例：

```json
{
  "estimatedCount": 18420,
  "calculatedAt": "2026-08-03T19:30:00+08:00",
  "dataVersion": "profile-20260803-1930",
  "calculationMode": "SNAPSHOT"
}
```

必须告诉前端：

- 数量是实时值还是估算值
- 使用的画像版本
- 计算时间
- 是否有数据延迟

### 7.8 AI 草稿

AI 生成结果只能保存为草稿。

草稿状态：

```text
GENERATED
NEEDS_CONFIRMATION
VALIDATED
INVALID
CONFIRMED
EXPIRED
```

只有 `VALIDATED` 状态可以进入确认流程。

确认时必须：

1. 检查草稿未过期
2. 再次校验 DSL
3. 必要时重新预估人数
4. 校验操作人权限
5. 调用原 Campaign 创建服务
6. 记录正式 Campaign ID
7. 更新草稿为 `CONFIRMED`

---

## 8. 功能二：AI 人群洞察与策略建议

### 8.1 设计边界

Java 后端负责计算事实，AI 负责解释事实。

正确方式：

```text
人群规则
→ 后端聚合指标
→ 基线对比
→ 脱敏 JSON
→ AI 生成洞察
```

错误方式：

```text
把全部用户行为日志发送给 AI
→ 让 AI 自行分析
```

### 8.2 聚合指标

根据当前项目已有数据，优先实现以下指标：

```text
audienceCount
activeRate7d
averageSpend30d
averageOrderCount30d
cartWithoutPurchaseRate
highValueRate
priceSensitiveRate
churnRiskRate
topCategories
memberLevelDistribution
```

全站基线可选：

```text
siteAverageActiveRate7d
siteAverageSpend30d
siteAverageCartWithoutPurchaseRate
```

若某指标当前项目无法可靠计算，就不要伪造，先不返回。

### 8.3 输入示例

```json
{
  "audienceCount": 18420,
  "metrics": {
    "activeRate7d": 0.78,
    "averageSpend30d": 426.3,
    "cartWithoutPurchaseRate": 0.42,
    "highValueRate": 0.27,
    "priceSensitiveRate": 0.35,
    "churnRiskRate": 0.18
  },
  "topCategories": [
    {
      "name": "数码",
      "rate": 0.38
    },
    {
      "name": "运动",
      "rate": 0.24
    }
  ],
  "baseline": {
    "activeRate7d": 0.59,
    "averageSpend30d": 381.7
  }
}
```

### 8.4 输出结构

```json
{
  "summary": "该人群活跃度高，但加购后的购买转化偏弱，价格敏感特征明显。",
  "findings": [
    {
      "title": "活跃度高于全站平均",
      "description": "目标人群7日活跃率为78%，全站基线为59%。",
      "evidenceKeys": [
        "metrics.activeRate7d",
        "baseline.activeRate7d"
      ],
      "importance": "HIGH"
    },
    {
      "title": "加购未购买比例较高",
      "description": "42%的目标用户存在加购后未购买行为。",
      "evidenceKeys": [
        "metrics.cartWithoutPurchaseRate"
      ],
      "importance": "HIGH"
    }
  ],
  "strategySuggestions": [
    {
      "type": "OFFER",
      "suggestion": "优先采用明确的满减优惠",
      "reason": "价格敏感用户占比为35%",
      "evidenceKeys": [
        "metrics.priceSensitiveRate"
      ]
    },
    {
      "type": "FREQUENCY",
      "suggestion": "限制短期重复触达",
      "reason": "流失风险用户占比为18%",
      "evidenceKeys": [
        "metrics.churnRiskRate"
      ]
    }
  ],
  "risks": [
    "该人群包含一定比例的流失风险用户，不适合高频轰炸"
  ]
}
```

### 8.5 证据校验

每个重要结论必须包含 `evidenceKeys`。

后端需要检查：

- Key 是否存在
- 值是否为空
- 描述中的数字是否与原始值一致
- AI 是否把比例写错
- AI 是否捏造不存在的对比

出现问题时：

- 该条洞察标记为无效，或
- 整体返回 `AI_OUTPUT_INVALID`

不要把不可靠解释直接展示给运营人员。

---

## 9. 功能三：AI 策略与营销文案

### 9.1 文案输入

输入必须包含真实业务事实：

```text
Campaign 目标
目标人群摘要
渠道
真实优惠信息
活动有效期
产品或品类
品牌语气
标题最大长度
正文最大长度
禁止词
必须包含的信息
```

示例：

```json
{
  "objective": "CONVERSION",
  "channel": "IN_APP",
  "audienceSummary": "高活跃、价格敏感、加购未购买比例较高",
  "promotionFacts": [
    "满300减30",
    "优惠有效期至2026年8月5日"
  ],
  "tone": "友好直接",
  "titleMaxLength": 24,
  "bodyMaxLength": 80,
  "variantCount": 3
}
```

### 9.2 输出三个差异化版本

固定生成：

1. 直接利益型
2. 适度紧迫型
3. 个性推荐型

示例：

```json
{
  "variants": [
    {
      "type": "DIRECT_BENEFIT",
      "title": "购物车好物，满300减30",
      "body": "你关注的商品还在购物车中，满300减30优惠已开放。",
      "strategy": "直接表达优惠利益"
    },
    {
      "type": "URGENCY",
      "title": "满减优惠即将结束",
      "body": "购物车商品仍可购买，满300减30优惠有效至8月5日。",
      "strategy": "基于真实截止时间制造适度紧迫感"
    },
    {
      "type": "PERSONALIZED",
      "title": "你关注的好物有新优惠",
      "body": "近期关注的商品可享满300减30，点击查看当前优惠。",
      "strategy": "强调用户近期兴趣"
    }
  ]
}
```

### 9.3 文案校验

实现 `ContentFactValidator`。

至少校验：

- 标题长度
- 正文长度
- 禁止词
- 未替换模板变量
- 是否出现未提供的优惠数字
- 是否改变优惠门槛
- 是否捏造活动期限
- 是否捏造库存不足
- 是否出现“最后一天”等不真实表述
- 是否包含个人敏感字段

事实来源只允许来自 `promotionFacts` 和明确业务输入。

例如业务输入是“满300减30”，模型生成“全场8折”，必须拒绝。

### 9.4 策略建议边界

AI 可以根据人群聚合信息建议：

- 优惠表达方式
- 文案风格
- 是否需要控制频率
- 是否更适合召回型文案
- 哪种人群特征值得强调

AI 暂时不能直接决定：

- 自动提高优惠力度
- 自动修改活动预算
- 自动选择未经配置的渠道
- 自动更改用户频控
- 自动决定上线

---

## 10. 功能四：AI 活动复盘

### 10.1 触发方式

Campaign 结束后，由 XXL-JOB 执行活动指标汇总。

建议流程：

```text
Campaign 结束
→ XXL-JOB 检查待复盘活动
→ 后端计算活动指标
→ 保存 performance summary
→ 调用 AI 生成复盘
→ 保存结构化复盘结果
```

AI 调用失败时：

- 保留后端计算的原始指标
- 标记复盘生成失败
- 不影响活动状态
- 允许手动重新生成

### 10.2 后端负责计算的指标

```text
targetAudienceCount
sentCount
deliveredCount
clickedCount
convertedCount
unsubscribeCount
deliveryRate
clickRate
conversionRate
unsubscribeRate
historicalAverageClickRate
historicalAverageConversionRate
bestContentVariant
```

核心比率必须由 Java 后端计算，不能由 AI 自行计算。

### 10.3 复盘输入示例

```json
{
  "campaignId": 1024,
  "objective": "CONVERSION",
  "metrics": {
    "sentCount": 18000,
    "deliveryRate": 0.97,
    "clickRate": 0.126,
    "conversionRate": 0.041,
    "unsubscribeRate": 0.003
  },
  "historicalBaseline": {
    "clickRate": 0.091,
    "conversionRate": 0.038
  },
  "contentVariants": [
    {
      "type": "DIRECT_BENEFIT",
      "clickRate": 0.143,
      "conversionRate": 0.047
    },
    {
      "type": "URGENCY",
      "clickRate": 0.118,
      "conversionRate": 0.039
    },
    {
      "type": "PERSONALIZED",
      "clickRate": 0.112,
      "conversionRate": 0.037
    }
  ]
}
```

### 10.4 复盘输出结构

```json
{
  "summary": "本次活动点击表现优于历史平均，转化率小幅提升。",
  "highlights": [
    {
      "title": "点击率明显提升",
      "description": "本次点击率为12.6%，高于历史平均9.1%。",
      "evidenceKeys": [
        "metrics.clickRate",
        "historicalBaseline.clickRate"
      ]
    }
  ],
  "problems": [
    {
      "title": "点击到转化仍存在损耗",
      "description": "点击率提升明显，但转化率只比历史平均高0.3个百分点。",
      "evidenceKeys": [
        "metrics.conversionRate",
        "historicalBaseline.conversionRate"
      ]
    }
  ],
  "nextActions": [
    {
      "action": "下一次优先使用直接利益型文案",
      "reason": "该版本点击率和转化率均为最高",
      "evidenceKeys": [
        "contentVariants"
      ]
    },
    {
      "action": "进一步检查落地页和结算链路",
      "reason": "点击提升幅度大于转化提升幅度",
      "evidenceKeys": [
        "metrics.clickRate",
        "metrics.conversionRate"
      ]
    }
  ],
  "limitations": [
    "当前结论未控制不同用户分群带来的偏差"
  ]
}
```

### 10.5 复盘要求

- AI 不得捏造未提供的数据
- 所有数字必须能在输入中找到
- 必须提供证据字段
- 必须区分事实和建议
- 数据不足时明确返回限制
- 复盘用于辅助运营决策，不自动修改下一次活动

---

## 11. API 设计

### 11.1 解析自然语言 Campaign

```http
POST /api/ai/campaigns/parse
```

请求：

```json
{
  "text": "筛选最近7天活跃5天以上、30天消费超过500元且3天没下单的用户，今晚8点发送满300减30站内信",
  "timezone": "Asia/Shanghai"
}
```

响应：

```json
{
  "requestId": "ai_req_xxx",
  "draftId": 1001,
  "status": "VALIDATED",
  "dsl": {},
  "estimatedAudience": {
    "count": 18420,
    "dataVersion": "profile-20260803-1930"
  },
  "missingFields": [],
  "warnings": []
}
```

### 11.2 更新并重新校验草稿

```http
PUT /api/ai/campaigns/drafts/{draftId}
```

用途：

- 前端修改 AI 生成结果
- 补充时间
- 补充真实优惠
- 调整规则条件

更新后重新执行完整校验。

### 11.3 生成人群洞察

```http
POST /api/ai/campaigns/drafts/{draftId}/insight
```

响应：

```json
{
  "metrics": {},
  "insight": {}
}
```

### 11.4 生成营销文案

```http
POST /api/ai/campaigns/drafts/{draftId}/contents
```

请求：

```json
{
  "tone": "友好直接",
  "variantCount": 3
}
```

优惠事实优先从草稿中读取，不允许前端传入与草稿冲突的数据。

### 11.5 确认创建 Campaign

```http
POST /api/campaigns/from-ai-draft/{draftId}
```

该接口应属于 Campaign 业务入口，而不是由 AI Controller 自行落库。

### 11.6 查询活动复盘

```http
GET /api/ai/campaigns/{campaignId}/review
```

### 11.7 手动重新生成复盘

```http
POST /api/ai/campaigns/{campaignId}/review/regenerate
```

需要权限和幂等控制。

---

## 12. 数据库设计

### 12.1 AI Campaign 草稿表

```sql
CREATE TABLE campaign_ai_draft (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    operator_id BIGINT NULL,
    source_text TEXT NOT NULL,
    schema_version INT NOT NULL,
    dsl_json JSON NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    validation_errors_json JSON NULL,
    warnings_json JSON NULL,
    estimated_audience_count BIGINT NULL,
    profile_data_version VARCHAR(64) NULL,
    confirmed_campaign_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    expires_at DATETIME NULL,
    confirmed_at DATETIME NULL,
    INDEX idx_ai_draft_operator_created (operator_id, created_at),
    INDEX idx_ai_draft_status (validation_status)
);
```

如当前 MySQL 版本或项目规范不使用 JSON 类型，可改为 `LONGTEXT`。

### 12.2 AI 调用记录表

```sql
CREATE TABLE ai_generation_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    operator_id BIGINT NULL,
    task_type VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    sanitized_input_json JSON NULL,
    structured_output_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    latency_ms BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_ai_request_id (request_id),
    INDEX idx_ai_task_created (task_type, created_at)
);
```

不建议长期保存包含敏感信息的完整 Prompt。

### 12.3 Campaign 效果摘要表

```sql
CREATE TABLE campaign_performance_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    target_audience_count BIGINT NOT NULL DEFAULT 0,
    sent_count BIGINT NOT NULL DEFAULT 0,
    delivered_count BIGINT NOT NULL DEFAULT 0,
    clicked_count BIGINT NOT NULL DEFAULT 0,
    converted_count BIGINT NOT NULL DEFAULT 0,
    unsubscribe_count BIGINT NOT NULL DEFAULT 0,
    delivery_rate DECIMAL(10,6) NULL,
    click_rate DECIMAL(10,6) NULL,
    conversion_rate DECIMAL(10,6) NULL,
    unsubscribe_rate DECIMAL(10,6) NULL,
    baseline_json JSON NULL,
    variant_metrics_json JSON NULL,
    calculated_at DATETIME NOT NULL,
    UNIQUE KEY uk_campaign_performance (campaign_id)
);
```

### 12.4 AI Campaign 复盘表

```sql
CREATE TABLE campaign_ai_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    performance_summary_id BIGINT NOT NULL,
    review_json JSON NOT NULL,
    model VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_campaign_ai_review (campaign_id)
);
```

---

## 13. Prompt 管理

Prompt 文件放在：

```text
pulseflow-ai/src/main/resources/prompts/
├── campaign-intent-v1.md
├── audience-insight-v1.md
├── campaign-content-v1.md
└── campaign-review-v1.md
```

第一版不做数据库 Prompt 平台。

### 13.1 Campaign Intent Prompt 必须约束

- 只能使用提供的字段
- 只能使用提供的操作符
- 不能生成 SQL
- 不能创造标签
- 不能创造渠道
- 不能猜测时间和优惠
- 信息不足时返回 `missingFields`
- 只输出 JSON
- 数值类型必须正确
- 不输出 Markdown 解释

### 13.2 Audience Insight Prompt 必须约束

- 只能解释输入指标
- 所有数字必须来源于输入
- 每条结论必须带 `evidenceKeys`
- 不得根据相关性推断因果关系
- 数据不足时明确说明
- 只输出 JSON

### 13.3 Content Prompt 必须约束

- 只能使用输入中的优惠事实
- 不得修改优惠门槛
- 不得捏造活动期限
- 不得虚构库存紧张
- 每个版本必须明显不同
- 遵守渠道长度限制
- 只输出 JSON

### 13.4 Review Prompt 必须约束

- 核心指标已由后端计算
- 不得重新发明计算口径
- 事实、问题和建议分开
- 所有数字带证据
- 不得自动提出提高优惠等高风险动作
- 只输出 JSON

---

## 14. 错误处理

统一错误码建议：

```text
AI_DISABLED
AI_PROVIDER_TIMEOUT
AI_PROVIDER_UNAVAILABLE
AI_PROVIDER_RATE_LIMITED
AI_EMPTY_RESPONSE
AI_INVALID_JSON
AI_OUTPUT_SCHEMA_INVALID
AI_UNKNOWN_FIELD
AI_INVALID_OPERATOR
AI_INVALID_VALUE
AI_MISSING_REQUIRED_FACT
AI_CONTENT_FACT_CONFLICT
AI_AUDIENCE_PREVIEW_FAILED
AI_REVIEW_DATA_NOT_READY
AI_INTERNAL_ERROR
```

要求：

- 不直接向前端返回厂商堆栈
- 日志中保留 requestId 和 traceId
- 解析失败和业务校验失败分开
- AI 失败不能导致 Campaign 主链路回滚
- 活动复盘失败可以重试，但不能重复插入多条结果

---

## 15. 可观测性

至少记录：

```text
AI 任务类型
模型
成功或失败
延迟
Token 使用量
JSON 解析失败次数
DSL 校验失败次数
文案事实冲突次数
活动复盘失败次数
```

若项目已接入 Micrometer，增加：

```text
pulseflow_ai_requests_total
pulseflow_ai_request_latency
pulseflow_ai_tokens_total
pulseflow_ai_parse_failures_total
pulseflow_ai_validation_failures_total
pulseflow_ai_fact_conflicts_total
pulseflow_ai_review_failures_total
```

所有日志带：

```text
requestId
traceId
operatorId
taskType
provider
model
promptVersion
campaignId
draftId
```

---

## 16. 测试方案

### 16.1 单元测试

#### Campaign DSL

- 正常 AND 条件
- 正常 OR 条件
- 未知字段
- 错误字段类型
- 非法操作符
- 数值越界
- 缺少时区
- 发送时间早于当前时间
- 超过最大条件数量
- 超过最大嵌套层级
- 模型生成 SQL 字段
- 模型生成不存在渠道
- 缺失真实优惠信息

#### AI 输出解析

- 正常 JSON
- Markdown 代码块包裹 JSON
- 非法 JSON
- 空响应
- 缺少必填字段
- 多余字段
- 错误枚举
- 类型错误

#### 人群洞察

- 正常 evidenceKeys
- 不存在的 evidenceKey
- AI 改写错误数字
- AI 捏造基线
- 输入数据不足

#### 文案校验

- 正常三个版本
- 标题超长
- 正文超长
- 捏造折扣
- 修改满减门槛
- 捏造截止时间
- 使用禁止词
- 包含未替换变量
- 版本内容重复度过高

#### 活动复盘

- 正常复盘
- 没有 performance summary
- AI 捏造数字
- evidenceKey 不存在
- 重复生成保持幂等

### 16.2 集成测试

必须覆盖完整链路：

```text
自然语言
→ Fake AI 返回 DSL
→ JSON 解析
→ DSL 校验
→ 人群预估
→ 草稿保存
→ 洞察生成
→ 文案生成
→ 确认创建 Campaign
```

以及：

```text
Campaign 结束
→ 指标汇总
→ Fake AI 生成复盘
→ 复盘保存
→ 查询接口返回
```

### 16.3 外部模型测试

真实模型测试：

- 默认不在 CI 中执行
- 需要环境变量显式开启
- 使用固定输入
- 不依赖输出文案完全一致
- 只校验结构、字段和业务约束
- 控制调用次数和 Token

---

## 17. 分阶段实施顺序

### 阶段 0：现状分析

完成：

- 项目扫描
- 现有规则模型确认
- 画像字段确认
- 数据库迁移方案确认
- 输出架构分析文档

验收：

- 未修改核心业务
- 文档列出真实文件路径
- 明确可复用接口

### 阶段 1：AI 基础设施

完成：

- 新增 `pulseflow-ai`
- AI 配置
- `AiModelClient`
- OpenAI Compatible Client
- Fake Client
- 基础调用记录
- 错误码

验收：

- AI 关闭时项目正常启动
- Fake Client 测试通过
- API Key 未进入仓库

### 阶段 2：AI Campaign 创建

完成：

- Campaign DSL
- `AiFieldRegistry`
- Prompt
- 输出解析
- DSL 校验
- 草稿表
- 解析 API

验收：

- 自然语言可以生成结构化草稿
- 不存在字段被拒绝
- 模糊信息进入待确认状态
- AI 不直接创建 Campaign

### 阶段 3：人群预估与确认

完成：

- DSL 转现有 Rule
- 人群预估
- 草稿过期机制
- 用户确认创建
- 草稿关联正式 Campaign

验收：

- 合法草稿可以预估
- 非法草稿不执行查询
- 确认时重新校验
- 正式执行仍走原 Campaign 服务

### 阶段 4：人群洞察与策略

完成：

- 聚合指标服务
- 基线对比
- Insight Prompt
- 结构化输出
- evidenceKeys 校验

验收：

- 不读取用户级明细
- 每条关键结论有证据
- 错误数字被拒绝

### 阶段 5：文案生成

完成：

- Content Prompt
- 三种文案
- 长度校验
- 事实一致性校验
- 生成 API

验收：

- 返回三个差异明显的版本
- 不得捏造优惠
- 不合规文案不可进入 Campaign

### 阶段 6：活动复盘

完成：

- XXL-JOB 汇总任务
- Performance Summary
- Review Prompt
- 结构化复盘
- 保存和查询接口
- 手动重试

验收：

- 核心指标由后端计算
- AI 只做解释
- AI 失败不影响 Campaign
- 同一活动复盘保持幂等

### 阶段 7：文档和回归

完成：

```text
docs/ai-current-architecture-analysis.md
docs/ai-campaign-copilot-design.md
docs/ai-api-examples.md
docs/ai-prompt-design.md
docs/ai-test-report.md
```

更新：

```text
README.md
```

README 增加：

- AI 模块定位
- 架构图
- 功能演示
- 配置方式
- Mock 方式
- 安全边界
- API 示例
- 测试结果

---

## 18. Trae 执行约束

1. 先分析，后编码。
2. 每次只推进一个阶段。
3. 不重构与本阶段无关的代码。
4. 不改变现有 Kafka Topic 语义。
5. 不改变用户画像计算口径。
6. 不绕过原 Campaign 规则引擎。
7. 不引入多 Agent。
8. 不引入向量数据库。
9. 不在测试中默认调用真实模型。
10. 不提交 API Key。
11. 不让 AI 生成可执行 SQL。
12. 不把模型自由文本直接当作业务结果。
13. 不向模型发送用户级敏感数据。
14. AI 功能必须支持关闭。
15. AI 调用失败不能破坏原有系统。
16. 优先复用现有异常、日志、Trace 和持久化规范。
17. 每阶段完成后执行定向测试和完整回归。
18. 不为了“架构完整”创建大量空接口或无意义抽象。
19. 类名、包名和表名应结合现有项目规范调整。
20. 未确认项目现状前，不允许假设已有能力。

---

## 19. 每阶段报告格式

每完成一个阶段，必须输出：

```text
1. 本阶段目标
2. 实际完成内容
3. 新增文件
4. 修改文件
5. 数据库变更
6. 核心设计决策
7. 构建结果
8. 定向测试结果
9. 完整回归结果
10. 失败测试分类
11. 已知问题
12. 下一阶段建议
```

如果完整回归存在原有失败，必须区分：

```text
本轮引入失败
历史已有失败
环境相关失败
```

不得只写“测试基本通过”。

---

## 20. 端到端验收场景

### 20.1 创建输入

```text
筛选最近7天活跃不少于5天、最近30天消费超过500元，
并且最近3天没有购买的用户。
今晚8点通过站内信发送满300减30优惠，
每个用户24小时最多触达一次。
```

### 20.2 预期结果

系统依次完成：

1. AI 生成 Campaign DSL
2. DSL 字段和操作符校验
3. 时间和频控校验
4. 人群预估
5. AI 草稿保存
6. 后端计算人群聚合指标
7. AI 生成人群洞察
8. AI 生成策略建议
9. AI 生成三个文案版本
10. 文案事实校验
11. 用户人工确认
12. 原 Campaign 服务创建正式活动
13. Kafka 和触达链路正常执行
14. 点击和转化事件完成归因
15. XXL-JOB 汇总活动指标
16. AI 生成结构化复盘

### 20.3 最终链路

```text
自然语言运营需求
→ AI Campaign DSL
→ 白名单与业务校验
→ 人群预估
→ 聚合画像
→ AI 人群洞察
→ AI 策略与文案
→ 人工确认
→ Campaign 规则引擎
→ 频控与幂等
→ Kafka 事件
→ 消息触达
→ 点击与转化归因
→ XXL-JOB 指标汇总
→ AI 活动复盘
```

---

## 21. 最终项目定位

完成后，PulseFlow 可描述为：

> PulseFlow 是一个事件驱动的智能用户运营平台。系统通过 Kafka、Redis 和 XXL-JOB 构建实时与窗口用户画像，通过 Campaign 规则引擎完成受控触达、频控、幂等和效果归因；在此基础上增加 AI Campaign Copilot，将自然语言运营目标转换为受约束的规则 DSL，基于聚合画像生成人群洞察和营销内容，并在活动结束后基于真实指标生成结构化复盘。AI 只负责理解与解释，最终执行始终由确定性的 Java 业务链路控制。

该定位能够同时体现：

- Java 后端工程能力
- 事件驱动架构
- Redis 实时状态
- XXL-JOB 离线与窗口计算
- Campaign 规则引擎
- 幂等、频控和归因
- LLM 结构化输出
- AI Guardrail
- AI 与传统业务系统的可靠集成
