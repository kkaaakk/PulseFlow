package com.pulseflow.ai.application;

import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.insight.AudienceMetrics;
import com.pulseflow.ai.domain.insight.InsightResult;
import com.pulseflow.ai.guardrail.AiOutputParser;
import com.pulseflow.ai.guardrail.InsightEvidenceValidator;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.infrastructure.persistence.AudienceMetricsAggregator;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AiRequest;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.prompt.AudienceInsightPromptBuilder;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.ai.support.AiTaskType;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Audience Insight pipeline.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Load draft DSL</li>
 *   <li>Compute aggregated {@link AudienceMetrics} (no user-level data)</li>
 *   <li>Wrap metrics + baseline into a single input object so evidenceKeys
 *       can reference {@code "metrics.xxx"} and {@code "baseline.xxx"}</li>
 *   <li>Build prompt → call {@link AiModelClient}</li>
 *   <li>Parse JSON → {@link InsightResult}</li>
 *   <li>{@link InsightEvidenceValidator}: drop fabricated findings, fail on
 *       fabricated summary numbers</li>
 *   <li>Audit + metrics</li>
 * </ol>
 *
 * <p>Never sends individual user rows to the model. Only aggregate counts,
 * ratios, and baselines.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudienceInsightService {

    private final CampaignAiDraftService draftService;
    private final AudienceMetricsAggregator metricsAggregator;
    private final AudienceInsightPromptBuilder promptBuilder;
    private final AiModelClient aiModelClient;
    private final AiOutputParser outputParser;
    private final InsightEvidenceValidator evidenceValidator;
    private final AiAuditService auditService;
    private final AiMetrics aiMetrics;

    /**
     * Generate an insight for the audience matched by the draft's DSL.
     *
     * @param draftId    the AI draft to inspect
     * @param operatorId nullable caller identity
     */
    public InsightOutput generate(Long draftId, Long operatorId) {
        // 1. Load DSL from draft
        CampaignDsl dsl = draftService.loadDsl(draftId);

        // 2. Compute aggregates
        AudienceMetrics metrics = metricsAggregator.aggregate(dsl);

        // 3. Wrap into input map for the LLM. Pull baseline to top level so
        //    evidenceKeys like "baseline.activeRate7d" work cleanly.
        Map<String, Object> input = buildLlmInput(metrics);
        String inputJson = JsonUtil.toJson(input);

        // 4. Build prompt
        AudienceInsightPromptBuilder.BuiltPrompt prompt = promptBuilder.build(input);
        String requestId = "ai_req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        AiRequest request = AiRequest.builder()
                .requestId(requestId)
                .taskType(AiTaskType.INSIGHT)
                .systemPrompt(prompt.systemPrompt())
                .userPrompt(prompt.userPrompt())
                .responseSchemaName("InsightResult")
                .temperature(0.2)
                .maxTokens(2048)
                .metadata(Map.of(
                        "operatorId", String.valueOf(operatorId),
                        "draftId", String.valueOf(draftId)))
                .build();

        // 5. Call model
        long started = System.currentTimeMillis();
        AiResponse response;
        try {
            response = aiModelClient.generateStructured(request);
        } catch (AiProviderException e) {
            aiMetrics.recordFailure(AiTaskType.INSIGHT, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            throw e;
        }
        aiMetrics.recordRequest(AiTaskType.INSIGHT, response.getProvider(),
                Duration.ofMillis(System.currentTimeMillis() - started), true);
        aiMetrics.recordTokens(AiTaskType.INSIGHT, response.getProvider(),
                safeInt(response.getPromptTokens()), safeInt(response.getCompletionTokens()));

        // 6. Parse + validate evidence
        InsightResult result;
        try {
            result = outputParser.parseObject(response.getRawContent(), InsightResult.class);
            result = evidenceValidator.validate(inputJson, result);
        } catch (AiOutputInvalidException e) {
            aiMetrics.recordFailure(AiTaskType.INSIGHT, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            throw e;
        }

        auditService.recordSuccess(request, response, prompt.version());
        return new InsightOutput(metrics, result, requestId, DataQuality.current());
    }

    /**
     * Build the LLM input JSON. The structure is:
     * <pre>
     * {
     *   "metrics": { ...all AudienceMetrics fields except baseline... },
     *   "baseline": { ...site-wide baseline map... }
     * }
     * </pre>
     * This shape aligns with the prompt template's evidenceKeys convention
     * ("metrics.activeRate7d", "baseline.averageSpend30d", etc.).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildLlmInput(AudienceMetrics metrics) {
        Map<String, Object> raw = JsonUtil.fromJson(JsonUtil.toJson(metrics), Map.class);
        Object baseline = raw.remove("baseline");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("calculatedAt", java.time.LocalDateTime.now().toString());
        input.put("profileDataVersion", "v1");
        input.put("baselineDataVersion", "v1");
        input.put("metrics", raw);
        input.put("baseline", baseline == null ? Map.of() : baseline);
        return input;
    }

    private int safeInt(Integer i) {
        return i == null ? 0 : i;
    }

    /**
     * Returned by {@link #generate}.
     */
    public record InsightOutput(
            AudienceMetrics metrics,
            InsightResult insight,
            String requestId,
            DataQuality dataQuality
    ) {}

    /**
     * Data quality metadata surfaced to the frontend so operators understand
     * the limitations of the current metrics (design §7.2.6).
     */
    public record DataQuality(
            String baselineType,
            List<String> proxyMetrics,
            List<String> unavailableMetrics
    ) {
        /** v1 static data quality info (matches AudienceMetricsAggregator). */
        public static DataQuality current() {
            return new DataQuality(
                    "CANDIDATE_POOL",
                    List.of("cartWithoutPurchaseRate"),
                    List.of("topCategories", "memberLevelDistribution"));
        }
    }
}
