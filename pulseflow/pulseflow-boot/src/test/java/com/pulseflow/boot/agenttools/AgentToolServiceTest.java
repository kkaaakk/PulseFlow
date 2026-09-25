package com.pulseflow.boot.agenttools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.boot.agenttools.AgentToolDtos.AudienceRequest;
import com.pulseflow.boot.agenttools.AgentToolDtos.AudienceResponse;
import com.pulseflow.boot.agenttools.AgentToolDtos.PerformanceResponse;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummary;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummaryMapper;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.dsl.DslValidationResult;
import com.pulseflow.campaign.preview.AudiencePreviewResult;
import com.pulseflow.campaign.preview.AudiencePreviewService;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentToolServiceTest {
    private final CampaignMapper campaigns = mock(CampaignMapper.class);
    private final CampaignPerformanceSummaryMapper summaries = mock(CampaignPerformanceSummaryMapper.class);
    private final CampaignDslValidator validator = mock(CampaignDslValidator.class);
    private final AudiencePreviewService preview = mock(AudiencePreviewService.class);
    private final AgentToolService service = new AgentToolService(campaigns, summaries, validator, preview);

    @Test
    void performanceReadsCalculatorSummaryWithoutWriting() {
        when(campaigns.selectById(9L)).thenReturn(Campaign.builder().id(9L).build());
        when(summaries.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                CampaignPerformanceSummary.builder().campaignId(9L).sentCount(100L)
                        .deliveredCount(80L).clickedCount(16L).convertedCount(4L)
                        .clickRate(new BigDecimal("0.2000"))
                        .calculatedAt(LocalDateTime.parse("2026-09-01T10:00:00")).build());
        PerformanceResponse response = service.performance(9L);
        assertThat(response.available()).isTrue();
        assertThat(response.clickRate()).isEqualByComparingTo("0.2000");
        assertThat(response.metadata().dataVersion()).startsWith("campaign-summary:");
        verify(summaries).selectOne(any(LambdaQueryWrapper.class));
        verifyNoInteractions(validator, preview);
    }

    @Test
    void audienceRunsExistingValidatorAndDoesNotEchoUnsafeWarning() {
        CampaignDsl dsl = CampaignDsl.builder().build();
        when(validator.validate(dsl)).thenReturn(DslValidationResult.ok(List.of()));
        when(preview.preview(dsl)).thenReturn(AudiencePreviewResult.builder()
                .estimatedCount(42).dataVersion("profile-v1")
                .warnings(List.of("no active users"))
                .build());
        AudienceResponse response = service.audience(new AudienceRequest(dsl));
        assertThat(response.estimatedCount()).isEqualTo(42);
        assertThat(response.validation().valid()).isTrue();
        assertThat(response.metadata().warnings()).containsExactly("preview_warning");
        verify(validator).validate(dsl);
        verify(preview).preview(dsl);
    }

    @Test
    void failedPreviewDoesNotPretendZeroIsAnAuthoritativeCount() {
        CampaignDsl dsl = CampaignDsl.builder().build();
        when(validator.validate(dsl)).thenReturn(DslValidationResult.ok(List.of()));
        when(preview.preview(dsl)).thenReturn(AudiencePreviewResult.builder()
                .estimatedCount(0).warnings(List.of("preview failed: raw sensitive detail"))
                .build());
        AudienceResponse response = service.audience(new AudienceRequest(dsl));
        assertThat(response.estimatedCount()).isNull();
        assertThat(response.metadata().warnings()).containsExactly("preview_unavailable");
        assertThat(response.toString()).doesNotContain("raw sensitive detail");
    }

    @Test
    void invalidAudienceSkipsPreview() {
        CampaignDsl dsl = CampaignDsl.builder().build();
        when(validator.validate(dsl)).thenReturn(DslValidationResult.invalid(List.of("unsafe value")));
        AudienceResponse response = service.audience(new AudienceRequest(dsl));
        assertThat(response.estimatedCount()).isNull();
        assertThat(response.validation().errors()).containsExactly("invalid_campaign_dsl");
        assertThat(response.toString()).doesNotContain("unsafe value");
        verifyNoInteractions(preview);
    }
}
