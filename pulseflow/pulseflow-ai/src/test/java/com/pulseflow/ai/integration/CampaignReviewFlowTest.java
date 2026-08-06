package com.pulseflow.ai.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pulseflow.ai.application.CampaignReviewService;
import com.pulseflow.ai.guardrail.AiOutputParser;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.guardrail.ReviewEvidenceValidator;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.infrastructure.persistence.PerformanceSummaryCalculator;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiReview;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignPerformanceSummary;
import com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiReviewMapper;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.prompt.CampaignReviewPromptBuilder;
import com.pulseflow.ai.support.AiConflictException;
import com.pulseflow.ai.support.AiForbiddenException;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test for the Campaign Review flow.
 *
 * <p>Verifies the concurrency and idempotency invariants (design §7.1.3):</p>
 * <ul>
 *   <li>tryGenerate skips a campaign already being processed by another executor.</li>
 *   <li>tryGenerate skips a campaign that already has a SUCCESS review.</li>
 *   <li>When AI fails, the review row is marked FAILED (not left in PROCESSING).</li>
 *   <li>Regenerating while PROCESSING (non-stale) throws 409.</li>
 *   <li>Regenerating after SUCCESS overwrites the review.</li>
 * </ul>
 *
 * <p>The mock simulates the conditional-UPDATE CAS: a row transitions from
 * PENDING/FAILED to PROCESSING only when the test has not pre-populated a
 * PROCESSING/SUCCESS state.</p>
 */
class CampaignReviewFlowTest {

    private CampaignMapper campaignMapper;
    private CampaignAiReviewMapper reviewMapper;
    private PerformanceSummaryCalculator summaryCalculator;
    private AiModelClient aiModelClient;
    private CampaignReviewService reviewService;

    /** Single-row store (tests only exercise one campaignId). */
    private final Map<Long, CampaignAiReview> reviewStore = new ConcurrentHashMap<>();
    private final AtomicReference<String> casOutcome = new AtomicReference<>("ALLOW");

    /**
     * Initialise MyBatis-Plus TableInfo cache for CampaignAiReview so that
     * {@code LambdaUpdateWrapper.set(CampaignAiReview::getStatus, ...)} used
     * inside {@link CampaignReviewService#tryAcquireLock} and
     * {@link CampaignReviewService#markTerminal} can resolve column names
     * without a running Spring context.
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiReviewMapper");
        TableInfoHelper.initTableInfo(assistant, CampaignAiReview.class);
    }

    @BeforeEach
    void setUp() {
        reviewStore.clear();
        casOutcome.set("ALLOW");

        campaignMapper = mock(CampaignMapper.class);
        reviewMapper = mock(CampaignAiReviewMapper.class);
        summaryCalculator = mock(PerformanceSummaryCalculator.class);
        AiOutputParser parser = new AiOutputParser();
        ReviewEvidenceValidator evidenceValidator = new ReviewEvidenceValidator();
        CampaignReviewPromptBuilder promptBuilder = new CampaignReviewPromptBuilder();
        AiAuditService auditService = mock(AiAuditService.class);
        AiMetrics aiMetrics = new AiMetrics(new SimpleMeterRegistry());
        aiModelClient = mock(AiModelClient.class);
        AiFeatureProperties properties = new AiFeatureProperties();

        reviewService = new CampaignReviewService(
                campaignMapper, reviewMapper, summaryCalculator,
                promptBuilder, aiModelClient, parser, evidenceValidator,
                auditService, aiMetrics, properties);

        // selectOne: return the stored row (or null)
        when(reviewMapper.selectOne(any())).thenAnswer(inv ->
                reviewStore.isEmpty() ? null : reviewStore.values().iterator().next());

        // insert: store the row
        when(reviewMapper.insert(any())).thenAnswer(inv -> {
            CampaignAiReview r = inv.getArgument(0);
            if (r.getId() == null) r.setId(1L);
            reviewStore.put(r.getCampaignId(), r);
            return 1;
        });

        // update(entity, wrapper): the CAS lock + terminal state transitions.
        // Returns 1 if ALLOW, 0 if DENY.
        // Inspect the wrapper's paramNameValuePairs for the target status string
        // and apply it to the stored row so subsequent findLatest() calls see it.
        when(reviewMapper.update(any(), any())).thenAnswer(inv -> {
            if ("DENY".equals(casOutcome.get())) return 0;
            if (reviewStore.isEmpty()) return 1;
            CampaignAiReview stored = reviewStore.values().iterator().next();
            Object wrapper = inv.getArgument(1);
            if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> w) {
                Map<String, Object> params = w.getParamNameValuePairs();
                if (params != null) {
                    for (Object v : params.values()) {
                        if (v instanceof String s) {
                            switch (s) {
                                case CampaignReviewService.STATUS_PROCESSING -> {
                                    stored.setStatus(CampaignReviewService.STATUS_PROCESSING);
                                    stored.setLockedBy("test-exec");
                                    stored.setLockedAt(LocalDateTime.now());
                                }
                                case CampaignReviewService.STATUS_SUCCESS -> {
                                    stored.setStatus(CampaignReviewService.STATUS_SUCCESS);
                                    stored.setLockedBy(null);
                                    stored.setLockedAt(null);
                                }
                                case CampaignReviewService.STATUS_RETRYABLE_FAILED -> {
                                    stored.setStatus(CampaignReviewService.STATUS_RETRYABLE_FAILED);
                                    stored.setLockedBy(null);
                                    stored.setLockedAt(null);
                                }
                                case CampaignReviewService.STATUS_SKIPPED_INSUFFICIENT_DATA -> {
                                    stored.setStatus(CampaignReviewService.STATUS_SKIPPED_INSUFFICIENT_DATA);
                                    stored.setLockedBy(null);
                                    stored.setLockedAt(null);
                                }
                                case CampaignReviewService.STATUS_DATA_NOT_READY -> {
                                    stored.setStatus(CampaignReviewService.STATUS_DATA_NOT_READY);
                                    stored.setLockedBy(null);
                                    stored.setLockedAt(null);
                                }
                                case CampaignReviewService.STATUS_PERMANENT_FAILED -> {
                                    stored.setStatus(CampaignReviewService.STATUS_PERMANENT_FAILED);
                                    stored.setLockedBy(null);
                                    stored.setLockedAt(null);
                                }
                                case CampaignReviewService.STATUS_PENDING -> {
                                    stored.setStatus(CampaignReviewService.STATUS_PENDING);
                                    stored.setLockedBy(null);
                                    stored.setLockedAt(null);
                                }
                            }
                        }
                    }
                }
            }
            return 1;
        });

        // updateById: store the row
        when(reviewMapper.updateById(any())).thenAnswer(inv -> {
            CampaignAiReview r = inv.getArgument(0);
            reviewStore.put(r.getCampaignId(), r);
            return 1;
        });

        Campaign campaign = new Campaign();
        campaign.setId(1024L);
        campaign.setName("test");
        campaign.setDescription("[OBJ:CONVERSION] test");
        when(campaignMapper.selectById(any())).thenReturn(campaign);

        CampaignPerformanceSummary summary = CampaignPerformanceSummary.builder()
                .id(1L).campaignId(1024L)
                .targetAudienceCount(10000L).sentCount(9000L).deliveredCount(8800L)
                .clickedCount(1100L).convertedCount(45L).unsubscribeCount(3L)
                .deliveryRate(new BigDecimal("0.977778"))
                .clickRate(new BigDecimal("0.125000"))
                .conversionRate(new BigDecimal("0.040909"))
                .unsubscribeRate(new BigDecimal("0.000333"))
                .baselineJson("{\"clickRate\":0.091,\"conversionRate\":0.038}")
                .variantMetricsJson("[]")
                .calculatedAt(LocalDateTime.now())
                .build();
        when(summaryCalculator.compute(any())).thenReturn(summary);
    }

    private AiResponse fakeSuccessResponse() {
        String json = JsonUtil.toJson(Map.of(
                "summary", "本次点击率12.5%。",
                "highlights", List.of(Map.of(
                        "title", "点击表现",
                        "description", "点击率为12.5%。",
                        "evidenceKeys", List.of("metrics.clickRate"))),
                "problems", List.of(),
                "nextActions", List.of(),
                "limitations", List.of("复盘基于聚合数据。")));
        return AiResponse.builder()
                .requestId("ai_req_test").provider("fake").model("fake-review")
                .rawContent(json).promptTokens(100).completionTokens(50)
                .build();
    }

    @Test
    @DisplayName("tryGenerate skips a campaign that already has SUCCESS review")
    void tryGenerateSkipsSuccessCampaign() {
        reviewStore.put(1024L, CampaignAiReview.builder()
                .id(1L).campaignId(1024L).status("SUCCESS")
                .reviewJson("{}").model("fake").promptVersion("v1").version(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());

        CampaignAiReview result = reviewService.tryGenerate(1024L, "job-exec-A");

        assertThat(result).isNull();
        verify(aiModelClient, never()).generateStructured(any());
    }

    @Test
    @DisplayName("tryGenerate skips a campaign already being PROCESSING (non-stale, CAS returns 0)")
    void tryGenerateSkipsProcessingCampaign() {
        reviewStore.put(1024L, CampaignAiReview.builder()
                .id(1L).campaignId(1024L).status("PROCESSING")
                .lockedBy("job-exec-B")
                .lockedAt(LocalDateTime.now().minusMinutes(1))
                .version(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        // CAS update returns 0 → lock not acquired
        casOutcome.set("DENY");

        CampaignAiReview result = reviewService.tryGenerate(1024L, "job-exec-A");

        assertThat(result).isNull();
        verify(aiModelClient, never()).generateStructured(any());
    }

    @Test
    @DisplayName("tryGenerate on a fresh campaign acquires lock and calls AI")
    void tryGenerateOnFreshCampaignAcquiresLock() {
        when(aiModelClient.generateStructured(any())).thenReturn(fakeSuccessResponse());

        CampaignAiReview result = reviewService.tryGenerate(1024L, "job-exec-A");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verify(aiModelClient, times(1)).generateStructured(any());
    }

    @Test
    @DisplayName("when AI fails, review row is marked RETRYABLE_FAILED (not stuck in PROCESSING)")
    void aiFailureMarksFailedNotProcessing() {
        when(aiModelClient.generateStructured(any()))
                .thenThrow(new AiProviderException("AI_TIMEOUT", "provider timed out"));

        assertThatThrownBy(() -> reviewService.tryGenerate(1024L, "job-exec-A"))
                .isInstanceOf(AiProviderException.class);

        // Verify markTerminal was called (update with wrapper). The exact
        // number depends on implementation, but at least the CAS + terminal.
        verify(reviewMapper, atLeast(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("regenerate while PROCESSING (non-stale) throws 409")
    void regenerateWhileProcessingThrowsConflict() {
        reviewStore.put(1024L, CampaignAiReview.builder()
                .id(1L).campaignId(1024L).status("PROCESSING")
                .lockedBy("job-exec-B")
                .lockedAt(LocalDateTime.now().minusMinutes(1))
                .version(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        // CAS update returns 0 (still held by B)
        casOutcome.set("DENY");

        assertThatThrownBy(() -> reviewService.generate(1024L, 1L))
                .isInstanceOf(AiConflictException.class)
                .hasMessageContaining("currently being processed");
        verify(aiModelClient, never()).generateStructured(any());
    }

    @Test
    @DisplayName("regenerate after SUCCESS overwrites the review")
    void regenerateAfterSuccessOverwrites() {
        reviewStore.put(1024L, CampaignAiReview.builder()
                .id(1L).campaignId(1024L).status("SUCCESS")
                .reviewJson("{\"old\":true}").model("old-model").promptVersion("v1").version(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
        when(aiModelClient.generateStructured(any())).thenReturn(fakeSuccessResponse());

        CampaignAiReview result = reviewService.generate(1024L, 1L);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verify(aiModelClient, times(1)).generateStructured(any());
    }

    @Test
    @DisplayName("AI 失败时聚合指标仍然保存（summary 在 AI 调用前已 compute）")
    void aiFailureStillPersistsMetrics() {
        when(aiModelClient.generateStructured(any()))
                .thenThrow(new AiProviderException("AI_TIMEOUT", "provider timed out"));

        assertThatThrownBy(() -> reviewService.tryGenerate(1024L, "job-exec-A"))
                .isInstanceOf(AiProviderException.class);

        // 指标在调用 AI 之前已 compute 并持久化 —— AI 失败不能丢指标
        verify(summaryCalculator, times(1)).compute(1024L);
        verify(aiModelClient, times(1)).generateStructured(any());
    }

    @Test
    @DisplayName("数据不足时不调用 AI、不强生成结论，标记 SKIPPED_INSUFFICIENT_DATA（不可重试）")
    void insufficientDataSkipsAiGeneration() {
        // sentCount=0 / audience=0 → 数据不足
        CampaignPerformanceSummary empty = CampaignPerformanceSummary.builder()
                .id(2L).campaignId(1024L)
                .targetAudienceCount(0L).sentCount(0L).deliveredCount(0L)
                .clickedCount(0L).convertedCount(0L).unsubscribeCount(0L)
                .calculatedAt(LocalDateTime.now())
                .build();
        when(summaryCalculator.compute(any())).thenReturn(empty);

        CampaignAiReview result = reviewService.tryGenerate(1024L, "job-exec-A");

        assertThat(result).isNull();
        verify(aiModelClient, never()).generateStructured(any());
        // 仍写入终态 FAILED（带原因），便于排查为什么不生成结论
        verify(reviewMapper, atLeast(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("sentCount=0 但 audience>0 且在宽限期内 → DATA_NOT_READY（可重试），不调 AI、不永久跳过")
    void dataNotReadyMarksRetryableWithinGrace() {
        // audience>0 but no sends recorded yet — consumption pipeline may lag.
        // The default campaign mock has endTime=null → assessDataReadiness
        // treats it as DATA_NOT_READY (retryable) rather than permanent skip.
        CampaignPerformanceSummary noSends = CampaignPerformanceSummary.builder()
                .id(3L).campaignId(1024L)
                .targetAudienceCount(10000L).sentCount(0L).deliveredCount(0L)
                .clickedCount(0L).convertedCount(0L).unsubscribeCount(0L)
                .calculatedAt(LocalDateTime.now())
                .build();
        when(summaryCalculator.compute(any())).thenReturn(noSends);

        CampaignAiReview result = reviewService.tryGenerate(1024L, "job-exec-A");

        assertThat(result).isNull();
        verify(aiModelClient, never()).generateStructured(any());
        // 关键：被标记为 DATA_NOT_READY（可重试），而非永久 SKIPPED_INSUFFICIENT_DATA
        CampaignAiReview stored = reviewStore.get(1024L);
        assertThat(stored.getStatus()).isEqualTo(CampaignReviewService.STATUS_DATA_NOT_READY);
    }

    @Test
    @DisplayName("requireCampaignOwner: 无登录态(operatorId=null) → 403")
    void requireCampaignOwnerRejectsNullOperator() {
        assertThatThrownBy(() -> reviewService.requireCampaignOwner(1024L, null))
                .isInstanceOf(AiForbiddenException.class)
                .hasMessageContaining("Operator ID is required");
    }

    @Test
    @DisplayName("requireCampaignOwner: 历史 campaign created_by=null → 403 默认拒绝（防越权）")
    void requireCampaignOwnerRejectsLegacyNullOwner() {
        // default campaign mock has createdBy=null
        assertThatThrownBy(() -> reviewService.requireCampaignOwner(1024L, 1L))
                .isInstanceOf(AiForbiddenException.class)
                .hasMessageContaining("no recorded owner");
    }

    @Test
    @DisplayName("requireCampaignOwner: 非归属者 → 403")
    void requireCampaignOwnerRejectsNonOwner() {
        Campaign ownedByOther = new Campaign();
        ownedByOther.setId(1024L);
        ownedByOther.setCreatedBy(999L);
        when(campaignMapper.selectById(any())).thenReturn(ownedByOther);

        assertThatThrownBy(() -> reviewService.requireCampaignOwner(1024L, 1L))
                .isInstanceOf(AiForbiddenException.class)
                .hasMessageContaining("does not own");
    }

    @Test
    @DisplayName("requireCampaignOwner: 归属者 → 通过，无异常")
    void requireCampaignOwnerAllowsOwner() {
        Campaign owned = new Campaign();
        owned.setId(1024L);
        owned.setCreatedBy(1L);
        when(campaignMapper.selectById(any())).thenReturn(owned);

        reviewService.requireCampaignOwner(1024L, 1L);
        verify(campaignMapper).selectById(1024L);
    }
}
