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
    /**
     * 验证从触达、点击和归因事实正确计算绩效计数与比率。
     */
    @Test
    void computesCountsAndRatesFromDeliveryClickAndAttributionFacts() {
        CampaignMapper campaigns = mock(CampaignMapper.class);
        DeliveryTaskMapper tasks = mock(DeliveryTaskMapper.class);
        ClickEventMapper clicks = mock(ClickEventMapper.class);
        AttributionRecordMapper attributions = mock(AttributionRecordMapper.class);
        CampaignPerformanceSummaryMapper summaries = mock(CampaignPerformanceSummaryMapper.class);
        when(campaigns.selectForSummaryUpdate(7L)).thenReturn(Campaign.builder().id(7L).build());
        var attempt = java.time.LocalDateTime.of(2026,11,2,12,0);
        when(tasks.selectList(any())).thenReturn(List.of(
                DeliveryTask.builder().id(1L).userId(10L).status("SENT").sentAt(attempt).firstSendAttemptAt(attempt).build(),
                DeliveryTask.builder().id(2L).userId(11L).status("FAILED").firstSendAttemptAt(attempt).build(),
                DeliveryTask.builder().id(3L).userId(11L).status("PENDING").build(),
                DeliveryTask.builder().id(4L).userId(11L).status("CANCELLED").build()));
        when(clicks.countValidUsers(7L)).thenReturn(1L);
        when(attributions.countValidConverters(7L)).thenReturn(1L);
        when(summaries.selectList(any())).thenReturn(List.of());

        CampaignPerformanceSummary actual = new PerformanceSummaryCalculator(
                campaigns, tasks, clicks, attributions, summaries).compute(7L);

        assertThat(actual.getTargetAudienceCount()).isEqualTo(2L);
        assertThat(actual.getSentCount()).isEqualTo(2L);
        assertThat(actual.getDeliveredCount()).isEqualTo(1L);
        assertThat(actual.getClickedCount()).isEqualTo(1L);
        assertThat(actual.getConvertedCount()).isEqualTo(1L);
        assertThat(actual.getDeliveryRate()).isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(actual.getClickRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(actual.getConversionRate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(summaries).upsert(actual);
    }
}
