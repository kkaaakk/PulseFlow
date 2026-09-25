package com.pulseflow.campaign.analytics;

import com.pulseflow.entity.*;
import com.pulseflow.mapper.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PerformanceSummaryCalculatorTest {
    @Test
    void computesCountsAndRatesFromDeliveryClickAndAttributionFacts() {
        CampaignMapper campaigns = mock(CampaignMapper.class);
        DeliveryTaskMapper tasks = mock(DeliveryTaskMapper.class);
        DeliveryRecordMapper deliveries = mock(DeliveryRecordMapper.class);
        ClickEventMapper clicks = mock(ClickEventMapper.class);
        AttributionRecordMapper attributions = mock(AttributionRecordMapper.class);
        CampaignPerformanceSummaryMapper summaries = mock(CampaignPerformanceSummaryMapper.class);
        when(campaigns.selectById(7L)).thenReturn(Campaign.builder().id(7L).build());
        when(tasks.selectList(any())).thenReturn(List.of(
                DeliveryTask.builder().id(1L).userId(10L).build(),
                DeliveryTask.builder().id(2L).userId(11L).build()));
        when(deliveries.selectList(any())).thenReturn(List.of(
                DeliveryRecord.builder().status("SENT").build(),
                DeliveryRecord.builder().status("FAILED").build()));
        when(clicks.selectList(any())).thenReturn(List.of(
                ClickEvent.builder().userId(10L).build(),
                ClickEvent.builder().userId(10L).build()));
        when(attributions.selectList(any())).thenReturn(List.of(
                AttributionRecord.builder().userId(10L).build()));
        when(summaries.selectList(any())).thenReturn(List.of());

        CampaignPerformanceSummary actual = new PerformanceSummaryCalculator(
                campaigns, tasks, deliveries, clicks, attributions, summaries).compute(7L);

        assertThat(actual.getTargetAudienceCount()).isEqualTo(2L);
        assertThat(actual.getSentCount()).isEqualTo(2L);
        assertThat(actual.getDeliveredCount()).isEqualTo(1L);
        assertThat(actual.getClickedCount()).isEqualTo(1L);
        assertThat(actual.getConvertedCount()).isEqualTo(1L);
        assertThat(actual.getDeliveryRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(actual.getClickRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(actual.getConversionRate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(summaries).insert(actual);
    }
}
