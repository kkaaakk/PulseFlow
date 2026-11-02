package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.AudienceRequest;
import com.pulseflow.boot.agenttools.AgentToolDtos.AudienceResponse;
import com.pulseflow.boot.agenttools.AgentToolDtos.Metadata;
import com.pulseflow.boot.agenttools.AgentToolDtos.PerformanceResponse;
import com.pulseflow.boot.agenttools.AgentToolDtos.Validation;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummary;
import com.pulseflow.campaign.analytics.PerformanceSummaryCalculator;
import com.pulseflow.campaign.dsl.DslValidationResult;
import com.pulseflow.campaign.exception.CampaignResourceNotFoundException;
import com.pulseflow.campaign.preview.AudiencePreviewResult;
import com.pulseflow.campaign.preview.AudiencePreviewService;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only adapters over existing Java business services and summaries. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentToolService {
    private final CampaignMapper campaigns;
    private final PerformanceSummaryCalculator calculator;
    private final CampaignDslValidator validator;
    private final AudiencePreviewService previewService;

    /**
     * 按共享计算器读取当前事实；保持只读 Tool 权限，不落库、不返回陈旧快照。
     */
    @WithSpan("agent-tools.performance")
    public PerformanceResponse performance(Long campaignId) {
        if (campaignId == null || campaignId <= 0) throw new IllegalArgumentException("invalid_campaign_id");
        Campaign campaign = campaigns.selectById(campaignId);
        if (campaign == null) throw new CampaignResourceNotFoundException("Campaign not found");
        CampaignPerformanceSummary summary = calculator.readCurrent(campaignId);
        Instant calculatedAt = toInstant(summary.getCalculatedAt());
        Metadata meta = new Metadata(UUID.randomUUID().toString(), Instant.now(),
                calculatedAt == null ? null : "campaign-summary:" + calculatedAt,
                "campaign-summary", List.of());
        return new PerformanceResponse(meta, campaignId, true,
                summary.getTargetAudienceCount(), summary.getSentCount(),
                summary.getDeliveredCount(), summary.getClickedCount(),
                summary.getConvertedCount(), summary.getDeliveryRate(), summary.getClickRate(),
                summary.getConversionRate(), calculatedAt);
    }

    /**
     * 先执行 DSL 校验，再预估汇总人群规模；校验或预估失败转为稳定错误码，不回传内部异常文本。
     */
    @WithSpan("agent-tools.audience-preview")
    public AudienceResponse audience(AudienceRequest request) {
        if (request == null || request.dsl() == null) throw new IllegalArgumentException("invalid_campaign_dsl");
        DslValidationResult checked = validator.validate(request.dsl());
        Validation validation = new Validation(checked.isValid(), checked.isNeedsConfirmation(),
                checked.getErrors().isEmpty() ? List.of() : List.of("invalid_campaign_dsl"),
                checked.getMissingFields().stream()
                        .filter(Set.of("promotionFacts", "schedule.sendAt")::contains).toList());
        if (!checked.getErrors().isEmpty()) {
            return new AudienceResponse(AgentMetricService.metadata("audience-preview",
                    List.of("validation_failed")), null, validation);
        }
        AudiencePreviewResult result = previewService.preview(request.dsl());
        boolean failed = result.getWarnings() != null && result.getWarnings().stream()
                .anyMatch(warning -> warning != null && warning.startsWith("preview failed:"));
        List<String> warnings = failed ? List.of("preview_unavailable")
                : result.getWarnings() == null || result.getWarnings().isEmpty()
                    ? List.of() : List.of("preview_warning");
        Metadata meta = new Metadata(UUID.randomUUID().toString(), Instant.now(),
                result.getDataVersion(), "audience-preview", warnings);
        return new AudienceResponse(meta, failed ? null : result.getEstimatedCount(), validation);
    }

    /**
     * 按指标服务的业务时区解释本地时间并转换为 Instant，空值原样保留。
     */
    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(AgentMetricService.BUSINESS_ZONE).toInstant();
    }
}
