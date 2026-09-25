"""Verifiable descriptions derived from Java aggregate responses."""

from pulseflow_agent.domain.contracts import (
    AttributionResponse,
    AudienceResponse,
    CompareResponse,
    PerformanceResponse,
    QueryResponse,
)


def _dimensions(values: object) -> str:
    if not isinstance(values, dict) or not values:
        return "ALL"
    return ",".join(
        f"{key.value}={value if value is not None else 'NULL'}"
        for key, value in values.items()
    )


def query_observation(response: QueryResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} value={row.value} sampleSize={row.sample_size}"
        for row in response.rows
    )
    return f"{response.metric.value} rows={len(response.rows)}: {rows or 'NO_DATA'}"


def compare_observation(response: CompareResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} current={row.current} baseline={row.baseline} "
        f"absoluteDelta={row.absolute_delta} relativeDelta={row.relative_delta} "
        f"currentSampleSize={row.current_sample_size} baselineSampleSize={row.baseline_sample_size}"
        for row in response.rows
    )
    return f"{response.metric.value} comparison rows={len(response.rows)}: {rows or 'NO_DATA'}"


def performance_observation(response: PerformanceResponse) -> str:
    if not response.available:
        return f"CAMPAIGN={response.campaign_id} summary=UNAVAILABLE"
    return (
        f"CAMPAIGN={response.campaign_id} sent={response.sent_count} "
        f"delivered={response.delivered_count} clicked={response.clicked_count} "
        f"converted={response.converted_count} clickRate={response.click_rate} "
        f"conversionRate={response.conversion_rate}"
    )


def attribution_observation(response: AttributionResponse) -> str:
    rows = "; ".join(
        f"{_dimensions(row.dimensions)} attributionCount={row.attribution_count} "
        f"uniqueConverters={row.unique_converters}"
        for row in response.rows
    )
    return f"attribution rows={len(response.rows)}: {rows or 'NO_DATA'}"


def audience_observation(response: AudienceResponse) -> str:
    count = "UNAVAILABLE" if response.estimated_count is None else str(response.estimated_count)
    return (
        f"audience estimatedCount={count} validationValid={response.validation.valid} "
        f"needsConfirmation={response.validation.needs_confirmation}"
    )
