"""Verifiable descriptions derived from Java aggregate responses."""

from pulseflow_agent.domain.contracts import (
    AttributionResponse,
    AudienceResponse,
    CompareResponse,
    PerformanceResponse,
    QueryResponse,
)


# 把维度键值整理为紧凑文本，为聚合结果提供统一的分组描述。
def _dimensions(values: object) -> str:
    if not isinstance(values, dict) or not values:
        return "ALL"
    return ",".join(
        f"{key.value}={value if value is not None else 'NULL'}"
        for key, value in values.items()
    )


# 将指标、分组值和样本量整理为摘要，供模型推理及证据展示使用。
def query_observation(response: QueryResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} value={row.value} sampleSize={row.sample_size}"
        for row in response.rows
    )
    return f"{response.metric.value} rows={len(response.rows)}: {rows or 'NO_DATA'}"


# 将当前期、基准期及差异整理为摘要，沿用 Java 的比较结果。
def compare_observation(response: CompareResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} current={row.current} baseline={row.baseline} "
        f"absoluteDelta={row.absolute_delta} relativeDelta={row.relative_delta} "
        f"currentSampleSize={row.current_sample_size} baselineSampleSize={row.baseline_sample_size}"
        for row in response.rows
    )
    return f"{response.metric.value} comparison rows={len(response.rows)}: {rows or 'NO_DATA'}"


# 从活动绩效提取权威数值，生成可被结论引用的简短观察。
def performance_observation(response: PerformanceResponse) -> str:
    if not response.available:
        return f"CAMPAIGN={response.campaign_id} summary=UNAVAILABLE"
    return (
        f"CAMPAIGN={response.campaign_id} sent={response.sent_count} "
        f"delivered={response.delivered_count} clicked={response.clicked_count} "
        f"converted={response.converted_count} clickRate={response.click_rate} "
        f"conversionRate={response.conversion_rate}"
    )


# 把归因分组次数整理为摘要，支持渠道或活动对比。
def attribution_observation(response: AttributionResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} attributionCount={row.attribution_count} "
        f"uniqueConverters={row.unique_converters}"
        for row in response.rows
    )
    return f"attribution rows={len(response.rows)}: {rows or 'NO_DATA'}"


# 汇总预估人数、校验通过及需确认标记；人数缺失时明确显示为不可用。
def audience_observation(response: AudienceResponse) -> str:
    count = "UNAVAILABLE" if response.estimated_count is None else str(response.estimated_count)
    return (
        f"audience estimatedCount={count} validationValid={response.validation.valid} "
        f"needsConfirmation={response.validation.needs_confirmation}"
    )
