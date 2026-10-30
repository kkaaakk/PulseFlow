"""Verifiable descriptions derived from Java aggregate responses."""

from pulseflow_agent.domain.contracts import (
    AttributionResponse,
    AudienceResponse,
    CompareResponse,
    PerformanceResponse,
    QueryResponse,
)


# 将聚合维度键值转换为紧凑文本，供模型读取分组含义。
def _dimensions(values: object) -> str:
    if not isinstance(values, dict) or not values:
        return "ALL"
    return ",".join(
        f"{key.value}={value if value is not None else 'NULL'}"
        for key, value in values.items()
    )


# 把 Java 指标行、样本量及警告整理为紧凑观察文本，不在 Python 重算指标。
def query_observation(response: QueryResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} value={row.value} sampleSize={row.sample_size}"
        for row in response.rows
    )
    return f"{response.metric.value} rows={len(response.rows)}: {rows or 'NO_DATA'}"


# 呈现 Java 计算的当前值、基期值、绝对与相对变化，并保留样本量信息。
def compare_observation(response: CompareResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} current={row.current} baseline={row.baseline} "
        f"absoluteDelta={row.absolute_delta} relativeDelta={row.relative_delta} "
        f"currentSampleSize={row.current_sample_size} baselineSampleSize={row.baseline_sample_size}"
        for row in response.rows
    )
    return f"{response.metric.value} comparison rows={len(response.rows)}: {rows or 'NO_DATA'}"


# 把已存在的 Campaign 绩效快照转换为文本，缺失快照时明确表达不可用。
def performance_observation(response: PerformanceResponse) -> str:
    if not response.available:
        return f"CAMPAIGN={response.campaign_id} summary=UNAVAILABLE"
    return (
        f"CAMPAIGN={response.campaign_id} sent={response.sent_count} "
        f"delivered={response.delivered_count} clicked={response.clicked_count} "
        f"converted={response.converted_count} clickRate={response.click_rate} "
        f"conversionRate={response.conversion_rate}"
    )


# 把按维度汇总的归因次数与去重转化用户数转换为观察文本。
def attribution_observation(response: AttributionResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} attributionCount={row.attribution_count} "
        f"uniqueConverters={row.unique_converters}"
        for row in response.rows
    )
    return f"attribution rows={len(response.rows)}: {rows or 'NO_DATA'}"


# 呈现聚合人群规模和 DSL 校验状态，保留预估不可用或需要补充信息的含义。
def audience_observation(response: AudienceResponse) -> str:
    count = "UNAVAILABLE" if response.estimated_count is None else str(response.estimated_count)
    return (
        f"audience estimatedCount={count} validationValid={response.validation.valid} "
        f"needsConfirmation={response.validation.needs_confirmation}"
    )
