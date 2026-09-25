"""Typed contract matching Java's aggregate-only internal tool API."""

from datetime import timedelta
from decimal import Decimal
from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class WireModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class Metric(StrEnum):
    SENT = "SENT"
    DELIVERED = "DELIVERED"
    CLICKS = "CLICKS"
    CONVERSIONS = "CONVERSIONS"
    CTR = "CTR"
    CONVERSION_RATE = "CONVERSION_RATE"
    ATTRIBUTED_CONVERSIONS = "ATTRIBUTED_CONVERSIONS"


class Dimension(StrEnum):
    CAMPAIGN = "CAMPAIGN"
    CHANNEL = "CHANNEL"
    DAY = "DAY"


class AttributionDimension(StrEnum):
    CAMPAIGN = "CAMPAIGN"
    CHANNEL = "CHANNEL"
    DAY = "DAY"
    MODEL = "MODEL"


class FilterField(StrEnum):
    CAMPAIGN_ID = "CAMPAIGN_ID"
    CHANNEL = "CHANNEL"


class Operator(StrEnum):
    EQ = "EQ"
    IN = "IN"


class TimeRange(WireModel):
    from_inclusive: AwareDatetime
    to_exclusive: AwareDatetime

    @model_validator(mode="after")
    def bound_window(self) -> "TimeRange":
        duration = self.to_exclusive - self.from_inclusive
        if duration <= timedelta(0) or duration > timedelta(days=31):
            raise ValueError("time range must be positive and at most 31 days")
        return self


class MetricFilter(WireModel):
    field: FilterField
    operator: Operator
    values: list[str] = Field(min_length=1, max_length=10)

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


class QueryMetricArgs(WireModel):
    metric: Metric
    time_range: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimensions: list[Dimension] = Field(default_factory=list, max_length=1)
    row_limit: int = Field(default=20, ge=1, le=20)


class CompareMetricArgs(WireModel):
    metric: Metric
    current_period: TimeRange
    baseline_period: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimensions: list[Dimension] = Field(default_factory=list, max_length=1)
    row_limit: int = Field(default=20, ge=1, le=20)

    @model_validator(mode="after")
    def no_day_comparison(self) -> "CompareMetricArgs":
        if Dimension.DAY in self.dimensions:
            raise ValueError("DAY comparison is not supported")
        return self


class BreakdownMetricArgs(WireModel):
    metric: Metric
    time_range: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimension: Dimension
    row_limit: int = Field(default=20, ge=1, le=20)


class AttributionArgs(WireModel):
    time_range: TimeRange
    filters: list[MetricFilter] = Field(default_factory=list, max_length=2)
    dimension: AttributionDimension
    row_limit: int = Field(default=20, ge=1, le=20)


class AudienceCondition(WireModel):
    field: str = Field(min_length=1, max_length=64)
    operator: Literal["EQ", "NE", "GT", "GTE", "LT", "LTE"]
    value: str | int | float | bool
    value_type: Literal["INTEGER", "DECIMAL", "STRING", "BOOLEAN"]


class AudienceGroup(WireModel):
    logic: Literal["AND", "OR"]
    conditions: list[AudienceCondition] = Field(min_length=1, max_length=10)


class CampaignSchedule(WireModel):
    type: Literal["ONCE"]
    send_at: str
    timezone: str


class FrequencyCap(WireModel):
    max_times: int = Field(gt=0)
    window_hours: int = Field(gt=0)


class PromotionFact(WireModel):
    type: str
    threshold: Decimal | None = None
    discount: Decimal | None = None
    rate: Decimal | None = None
    valid_until: str | None = None
    description: str | None = None


class CampaignDsl(WireModel):
    schema_version: Literal[1] = 1
    campaign_name: str = Field(min_length=1, max_length=128)
    objective: Literal["CONVERSION", "RETENTION", "ACTIVATION", "BRANDING"]
    audience: AudienceGroup
    channel: Literal["IN_APP", "PUSH", "EMAIL"]
    schedule: CampaignSchedule
    frequency_cap: FrequencyCap
    promotion_facts: list[PromotionFact] = Field(default_factory=list, max_length=10)


class PreviewAudienceArgs(WireModel):
    dsl: CampaignDsl


class CampaignPerformanceArgs(WireModel):
    campaign_id: int = Field(gt=0)


class ToolMetadata(WireModel):
    query_id: UUID
    generated_at: AwareDatetime
    data_version: str | None
    source: Literal["campaign-facts", "campaign-summary", "attribution-record", "audience-preview"]
    warnings: list[Literal[
        "row_limit_reached", "zero_denominator_rate_is_zero", "baseline_zero",
        "summary_unavailable", "preview_warning", "preview_unavailable", "validation_failed",
    ]]


class MetricRow(WireModel):
    dimensions: dict[Dimension, str | None]
    value: Decimal
    sample_size: int = Field(ge=0)


class QueryResponse(WireModel):
    metadata: ToolMetadata
    metric: Metric
    time_range: TimeRange
    dimensions: list[Dimension]
    rows: list[MetricRow] = Field(max_length=100)
    sample_size: int = Field(ge=0)


class CompareRow(WireModel):
    dimensions: dict[Dimension, str | None]
    current: Decimal
    baseline: Decimal
    absolute_delta: Decimal
    relative_delta: Decimal | None
    current_sample_size: int = Field(ge=0)
    baseline_sample_size: int = Field(ge=0)


class CompareResponse(WireModel):
    metadata: ToolMetadata
    metric: Metric
    current_period: TimeRange
    baseline_period: TimeRange
    dimensions: list[Dimension]
    rows: list[CompareRow] = Field(max_length=100)
    sample_size: int = Field(ge=0)


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


class AttributionRow(WireModel):
    dimensions: dict[AttributionDimension, str | None]
    attribution_count: int = Field(ge=0)
    unique_converters: int = Field(ge=0)


class AttributionResponse(WireModel):
    metadata: ToolMetadata
    time_range: TimeRange
    dimension: AttributionDimension
    rows: list[AttributionRow] = Field(max_length=100)


class AudienceValidation(WireModel):
    valid: bool
    needs_confirmation: bool
    errors: list[str]
    missing_fields: list[str]


class AudienceResponse(WireModel):
    metadata: ToolMetadata
    estimated_count: int | None = Field(ge=0)
    validation: AudienceValidation


ToolResponse = (
    QueryResponse | CompareResponse | PerformanceResponse | AttributionResponse | AudienceResponse
)


def safe_query(args: WireModel) -> dict[str, Any]:
    """Store only typed aggregate selectors, never a full free-text Campaign DSL."""
    if isinstance(args, PreviewAudienceArgs):
        return {
            "audience_conditions": len(args.dsl.audience.conditions),
            "channel": args.dsl.channel,
            "objective": args.dsl.objective,
        }
    return args.model_dump(mode="json", by_alias=True)
