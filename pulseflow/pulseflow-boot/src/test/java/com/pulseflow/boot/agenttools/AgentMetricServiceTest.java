package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentMetricServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AgentMetricService service = new AgentMetricService(jdbc);
    private final TimeRange period = new TimeRange(OffsetDateTime.parse("2026-09-01T00:00:00+08:00"),
            OffsetDateTime.parse("2026-09-08T00:00:00+08:00"));

    @Test
    void ctrUsesCalculatorFormulaAndReturnsOnlyAggregates() {
        fakeFacts();
        QueryResponse result = service.query(new QueryRequest(Metric.CTR, period,
                List.of(), List.of(Dimension.CHANNEL), 10));
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).dimensions()).containsEntry(Dimension.CHANNEL, "PUSH");
        assertThat(result.rows().get(0).value()).isEqualByComparingTo("0.2000");
        assertThat(result.sampleSize()).isEqualTo(80);
        assertThat(result.metadata().source()).isEqualTo("campaign-facts");
        assertThat(result.rows().get(0).toString()).doesNotContain("userId", "taskId", "SQL");
    }

    @Test
    void compareIsCalculatedInJavaAndZeroBaselineIsExplicit() {
        fakeFacts();
        CompareResponse result = service.compare(new CompareRequest(Metric.CTR, period, period,
                List.of(), List.of(Dimension.CHANNEL), 10));
        assertThat(result.rows().get(0).absoluteDelta()).isEqualByComparingTo("0.0000");
        assertThat(result.rows().get(0).relativeDelta()).isEqualByComparingTo("0.0000");
    }

    @Test
    void zeroBaselineProducesNullRelativeDeltaAndWarning() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            MapSqlParameterSource params = invocation.getArgument(1);
            if (params.getValue("fromTime").toString().startsWith("2026-08")) return List.of();
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM delivery_record")) {
                return List.of(Map.of("bucket", "PUSH", "total", 100L, "delivered", 80L));
            }
            if (sql.contains("FROM click_event")) return List.of(Map.of("bucket", "PUSH", "total", 16L));
            return List.of();
        });
        TimeRange baseline = new TimeRange(OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-08-08T00:00:00+08:00"));
        CompareResponse response = service.compare(new CompareRequest(Metric.CTR, period, baseline,
                null, List.of(Dimension.CHANNEL), 10));
        assertThat(response.rows().get(0).current()).isEqualByComparingTo("0.2000");
        assertThat(response.rows().get(0).baseline()).isEqualByComparingTo("0");
        assertThat(response.rows().get(0).relativeDelta()).isNull();
        assertThat(response.metadata().warnings()).contains("baseline_zero");
    }

    @Test
    void invalidRegistryValuesAndRangeAreRejectedBeforeJdbc() {
        assertThatThrownBy(() -> service.query(new QueryRequest(null, period, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new QueryRequest(Metric.CTR, period, null,
                List.of(Dimension.DAY, Dimension.CHANNEL), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new QueryRequest(Metric.CTR, period,
                List.of(new Filter(FilterField.CHANNEL, Operator.EQ, List.of("REGION"))), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new QueryRequest(Metric.CTR,
                new TimeRange(period.fromInclusive(), period.fromInclusive().plusDays(32)),
                null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new QueryRequest(Metric.CTR, period, null, null, 101)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rowLimitAndNullDimensionHaveStableMeaning() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM delivery_record")) return List.of(
                    Map.of("bucket", "EMAIL", "total", 1L, "delivered", BigDecimal.ONE),
                    Map.of("bucket", "PUSH", "total", 2L, "delivered", BigDecimal.ONE));
            return List.of();
        });
        QueryResponse result = service.breakdown(new BreakdownRequest(Metric.SENT, period,
                null, Dimension.CHANNEL, 1));
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).dimensions()).containsEntry(Dimension.CHANNEL, "EMAIL");
        assertThat(result.metadata().warnings()).contains("row_limit_reached");
    }

    @Test
    void attributionReturnsCountsWithoutRawRows() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(
                List.of(Map.of("bucket", "CLICK_LAST_TOUCH", "total", 7L, "unique_count", 5L)));
        AttributionResponse result = service.attribution(new AttributionRequest(period, null,
                AttributionDimension.MODEL, 10));
        assertThat(result.rows().get(0).attributionCount()).isEqualTo(7);
        assertThat(result.rows().get(0).uniqueConverters()).isEqualTo(5);
        assertThat(result.rows().get(0).toString()).doesNotContain("userId", "targetEventId");
    }

    @Test
    void filterValuesAreBoundParametersRatherThanSqlFragments() {
        fakeFacts();
        service.query(new QueryRequest(Metric.CLICKS, period, List.of(
                new Filter(FilterField.CAMPAIGN_ID, Operator.EQ, List.of("9")),
                new Filter(FilterField.CHANNEL, Operator.EQ, List.of("PUSH"))),
                List.of(Dimension.CHANNEL), 10));
        org.mockito.ArgumentCaptor<String> statements = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<MapSqlParameterSource> parameters =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(3)).queryForList(statements.capture(), parameters.capture());
        assertThat(statements.getAllValues()).allSatisfy(sql -> {
            assertThat(sql).contains("IN (:campaignIds)", "IN (:channels)", "LIMIT 101");
            assertThat(sql).doesNotContain("'PUSH'", "IN (9)");
        });
        assertThat(parameters.getAllValues().get(0).getValue("campaignIds"))
                .isEqualTo(List.of(9L));
    }

    private void fakeFacts() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM delivery_record")) {
                return List.of(Map.of("bucket", "PUSH", "total", 100L, "delivered", 80L));
            }
            if (sql.contains("FROM click_event")) return List.of(Map.of("bucket", "PUSH", "total", 16L));
            return List.of(Map.of("bucket", "PUSH", "converted", 4L, "attributed", 5L));
        });
    }
}
