"""Typed contract matching Java's aggregate-only internal tool API."""

from datetime import timedelta
from decimal import Decimal
from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


# 跨服务模型基类，统一 camelCase 别名并拒绝未知字段。
class WireModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


# 允许查询的聚合业务指标。
class Metric(StrEnum):
    SENT = "SENT"
    DELIVERED = "DELIVERED"
    CLICKS = "CLICKS"
    CONVERSIONS = "CONVERSIONS"
    CTR = "CTR"
    CONVERSION_RATE = "CONVERSION_RATE"
    ATTRIBUTED_CONVERSIONS = "ATTRIBUTED_CONVERSIONS"


# 指标查询支持的分组维度。
class Dimension(StrEnum):
    CAMPAIGN = "CAMPAIGN"
    CHANNEL = "CHANNEL"
    DAY = "DAY"


# 归因拆解支持的分组维度，含归因模型。
class AttributionDimension(StrEnum):
    CAMPAIGN = "CAMPAIGN"
    CHANNEL = "CHANNEL"
    DAY = "DAY"
    MODEL = "MODEL"


# 聚合查询允许使用的筛选字段。
class FilterField(StrEnum):
    CAMPAIGN_ID = "CAMPAIGN_ID"
    CHANNEL = "CHANNEL"


# 允许使用的比较操作符，避免模型或请求传入任意表达式。
class Operator(StrEnum):
    EQ = "EQ"
    IN = "IN"


# 带时区的左闭右开查询区间，数据库桶按业务时区计算。
class TimeRange(WireModel):
    from_inclusive: AwareDatetime
    to_exclusive: AwareDatetime

    # 校验带时区的左闭右开区间，跨度须大于零且不超过三十一天。
    @model_validator(mode="after")
    def bound_window(self) -> "TimeRange":
        duration = self.to_exclusive - self.from_inclusive
        if duration <= timedelta(0) or duration > timedelta(days=31):
            raise ValueError("time range must be positive and at most 31 days")
        return self


# 受限字段和操作符的指标筛选，约束值数量及白名单。
class MetricFilter(WireModel):
    field: FilterField
    operator: Operator
    values: list[str] = Field(min_length=1, max_length=10)

    # 校验 EQ/IN 值数量、非空约束及活动 ID、渠道白名单。
    @model_validator(mode="after")
    def valid_values(self) -> "MetricFilter":
        if self.operator == Operator.EQ and len(self.values) != 1:
            raise ValueError("EQ needs one value")
        if any(not value.strip() for value in self.values):
            raise ValueError("filter values cannot be blank")
        if self.field == FilterField.CAMPAIGN_ID:
            if any(not value.isdecimal() or int(value) <= 0 for value in self.values):
                raise ValueError("campaign ID must be positive")
        elif any(value not in {"IN_APP", "PUSH", "EMAIL"} for value in self.values):
            raise ValueError("unsupported channel")
        return self


# 指标查询工具参数，限制时间跨度、维度和返回行数。
class QueryMetricArgs(WireModel):
    metric: Metric
    time_range: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimensions: list[Dimension] = Field(default_factory=list, max_length=1)
    row_limit: int = Field(default=20, ge=1, le=20)


# 两期指标比较参数，显式区分当前期与基准期。
class CompareMetricArgs(WireModel):
    metric: Metric
    current_period: TimeRange
    baseline_period: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimensions: list[Dimension] = Field(default_factory=list, max_length=1)
    row_limit: int = Field(default=20, ge=1, le=20)

    # 拒绝按 DAY 对齐的周期比较，避免错误对齐不同日期。
    @model_validator(mode="after")
    def no_day_comparison(self) -> "CompareMetricArgs":
        if Dimension.DAY in self.dimensions:
            raise ValueError("DAY comparison is not supported")
        return self


# 单维度拆解工具参数，携带有界时间范围和筛选条件。
class BreakdownMetricArgs(WireModel):
    metric: Metric
    time_range: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimension: Dimension
    row_limit: int = Field(default=20, ge=1, le=20)


# 归因汇总工具参数，指定分组、时间与返回上限。
class AttributionArgs(WireModel):
    time_range: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimension: AttributionDimension
    row_limit: int = Field(default=20, ge=1, le=20)


# 单个人群条件，字段、类型和操作符均受注册表约束。
class AudienceCondition(WireModel):
    field: str = Field(min_length=1, max_length=64)
    operator: Literal["EQ", "NE", "GT", "GTE", "LT", "LTE"]
    value: str | int | float | bool
    value_type: Literal["INTEGER", "DECIMAL", "STRING", "BOOLEAN"]


# 单层 AND/OR 人群条件组，供 DSL 校验和预估使用。
class AudienceGroup(WireModel):
    logic: Literal["AND", "OR"]
    conditions: list[AudienceCondition] = Field(min_length=1, max_length=10)


# 活动计划配置，保存触发类型、发送时间及时区信息。
class CampaignSchedule(WireModel):
    type: Literal["ONCE"]
    send_at: str
    timezone: str


# 触达次数及小时窗口配置，确认活动时转换为执行频控限制。
class FrequencyCap(WireModel):
    max_times: int = Field(gt=0)
    window_hours: int = Field(gt=0)


# 操作员授权的优惠事实，文案和方案不得自行发明额外优惠。
class PromotionFact(WireModel):
    type: str = Field(min_length=1, max_length=32)
    threshold: Decimal | None = Field(default=None, ge=0)
    discount: Decimal | None = Field(default=None, ge=0)
    rate: Decimal | None = Field(default=None, gt=0, le=1)
    valid_until: str | None = Field(default=None, max_length=64)
    description: str | None = Field(default=None, max_length=1000)


# 类型化活动配置，串联人群、渠道、排程、频控和授权优惠事实。
class CampaignDsl(WireModel):
    schema_version: Literal[1] = 1
    campaign_name: str = Field(min_length=1, max_length=128)
    objective: Literal["CONVERSION", "RETENTION", "ACTIVATION", "BRANDING"]
    audience: AudienceGroup
    channel: Literal["IN_APP", "PUSH", "EMAIL"]
    schedule: CampaignSchedule
    frequency_cap: FrequencyCap
    promotion_facts: list[PromotionFact] = Field(default_factory=list, max_length=10)


# 人群预估工具参数，封装类型化活动 DSL。
class PreviewAudienceArgs(WireModel):
    dsl: CampaignDsl


# 活动绩效工具参数，仅接受合法活动 ID。
class CampaignPerformanceArgs(WireModel):
    campaign_id: int = Field(gt=0)


# Java 查询返回的追溯信息，供证据保存来源、版本和警告。
class ToolMetadata(WireModel):
    query_id: UUID
    generated_at: AwareDatetime
    data_version: str | None
    source: Literal[
        "campaign-facts",
        "campaign-summary",
        "attribution-record",
        "audience-preview",
        "campaign-draft",
    ]
    warnings: list[
        Literal[
            "row_limit_reached",
            "zero_denominator_rate_is_zero",
            "baseline_zero",
            "summary_unavailable",
            "preview_warning",
            "preview_unavailable",
            "validation_failed",
        ]
    ]


# 单个分组的指标值及样本量，维度空值保留源数据缺失语义。
class MetricRow(WireModel):
    dimensions: dict[Dimension, str | None]
    value: Decimal
    sample_size: int = Field(ge=0)


# 聚合查询结果，包含指标、区间、分组行及元数据。
class QueryResponse(WireModel):
    metadata: ToolMetadata
    metric: Metric
    time_range: TimeRange
    dimensions: list[Dimension]
    rows: list[MetricRow] = Field(max_length=100)
    sample_size: int = Field(ge=0)


# 单个分组的两期值和变化量，零基准时相对变化为空。
class CompareRow(WireModel):
    dimensions: dict[Dimension, str | None]
    current: Decimal
    baseline: Decimal
    absolute_delta: Decimal
    relative_delta: Decimal | None
    current_sample_size: int = Field(ge=0)
    baseline_sample_size: int = Field(ge=0)


# 周期比较结果，包含对齐分组和口径警告。
class CompareResponse(WireModel):
    metadata: ToolMetadata
    metric: Metric
    current_period: TimeRange
    baseline_period: TimeRange
    dimensions: list[Dimension]
    rows: list[CompareRow] = Field(max_length=100)
    sample_size: int = Field(ge=0)


# 内部工具返回的活动权威绩效。
class PerformanceResponse(WireModel):
    metadata: ToolMetadata
    campaign_id: int = Field(gt=0)
    available: bool
    target_audience_count: int | None
    sent_count: int | None
    delivered_count: int | None
    clicked_count: int | None
    converted_count: int | None
    delivery_rate: Decimal | None
    click_rate: Decimal | None
    conversion_rate: Decimal | None
    calculated_at: AwareDatetime | None


# 单个归因分组的次数与去重用户数。
class AttributionRow(WireModel):
    dimensions: dict[AttributionDimension, str | None]
    attribution_count: int = Field(ge=0)
    unique_converters: int = Field(ge=0)


# 归因聚合结果，保留维度、区间和查询来源。
class AttributionResponse(WireModel):
    metadata: ToolMetadata
    time_range: TimeRange
    dimension: AttributionDimension
    rows: list[AttributionRow] = Field(max_length=100)


# Java DSL 校验代码及缺失字段摘要，不包含原始错误正文。
class AudienceValidation(WireModel):
    valid: bool
    needs_confirmation: bool
    errors: list[str]
    missing_fields: list[str]


# 人群预估工具响应，包含校验状态、人数和查询元数据。
class AudienceResponse(WireModel):
    metadata: ToolMetadata
    estimated_count: int | None = Field(ge=0)
    validation: AudienceValidation


ToolResponse = (
    QueryResponse | CompareResponse | PerformanceResponse | AttributionResponse | AudienceResponse
)


# 生成安全聚合条件；人群预估只保存条件数量、渠道和目标，避免保存完整自由文本。
def safe_query(args: WireModel) -> dict[str, Any]:
    """Store only typed aggregate selectors, never a full free-text Campaign DSL."""
    if isinstance(args, PreviewAudienceArgs):
        return {
            "audience_conditions": len(args.dsl.audience.conditions),
            "channel": args.dsl.channel,
            "objective": args.dsl.objective,
        }
    return args.model_dump(mode="json", by_alias=True)
