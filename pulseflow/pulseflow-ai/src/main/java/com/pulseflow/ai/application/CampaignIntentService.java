package com.pulseflow.ai.application;

import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.DslValidationResult;
import com.pulseflow.ai.guardrail.AiOutputParser;
import com.pulseflow.ai.guardrail.CampaignDslValidator;
import com.pulseflow.ai.guardrail.SensitiveDataSanitizer;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AiRequest;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.prompt.CampaignIntentPromptBuilder;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.ai.support.AiTaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Natural-language → Campaign DSL pipeline.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Sanitise input text</li>
 *   <li>Build prompt with live field registry</li>
 *   <li>Call {@link AiModelClient}</li>
 *   <li>Parse JSON (strip markdown fences)</li>
 *   <li>Validate via {@link CampaignDslValidator}</li>
 *   <li>Persist draft via {@link CampaignAiDraftService}</li>
 * </ol>
 *
 * <p>AI failure → {@link AiProviderException}; output failure →
 * {@link AiOutputInvalidException}. Both are recorded in ai_generation_record.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignIntentService {

    private final AiModelClient aiModelClient;
    private final CampaignIntentPromptBuilder promptBuilder;
    private final AiOutputParser outputParser;
    private final CampaignDslValidator validator;
    private final SensitiveDataSanitizer sanitizer;
    private final AudiencePreviewService audiencePreviewService;
    private final CampaignAiDraftService draftService;
    private final AiAuditService auditService;
    private final AiMetrics metrics;

    /**
     * @param operatorId nullable; present when called by an authenticated user
     * @param text       the natural-language brief
     * @param timezone   ISO ZoneId, e.g. "Asia/Shanghai"
     */
    public ParseResult parse(Long operatorId, String text, String timezone) {
        sanitizer.inspectText(text);
        String requestId = "ai_req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        CampaignIntentPromptBuilder.BuiltPrompt prompt = promptBuilder.build(text, timezone);
        AiRequest request = AiRequest.builder()
                .requestId(requestId)
                .taskType(AiTaskType.PARSE_DSL)
                .systemPrompt(prompt.systemPrompt())
                .userPrompt(prompt.userPrompt())
                .responseSchemaName("CampaignDsl")
                .temperature(0.1)
                .maxTokens(2048)
                .metadata(Map.of("operatorId", String.valueOf(operatorId)))
                .build();

        long started = System.currentTimeMillis();
        AiResponse response;
        try {
            response = aiModelClient.generateStructured(request);
        } catch (AiProviderException e) {
            metrics.recordFailure(AiTaskType.PARSE_DSL, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            throw e;
        }
        metrics.recordRequest(AiTaskType.PARSE_DSL, response.getProvider(),
                Duration.ofMillis(System.currentTimeMillis() - started), true);
        metrics.recordTokens(AiTaskType.PARSE_DSL, response.getProvider(),
                safeInt(response.getPromptTokens()), safeInt(response.getCompletionTokens()));

        // Parse + validate
        CampaignDsl dsl;
        try {
            dsl = outputParser.parseObject(response.getRawContent(), CampaignDsl.class);
        } catch (AiOutputInvalidException e) {
            metrics.recordFailure(AiTaskType.PARSE_DSL, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            throw e;
        }

        DslValidationResult validation = validator.validate(dsl);
        if (validation.getErrors() != null && !validation.getErrors().isEmpty()) {
            metrics.recordFailure(AiTaskType.PARSE_DSL, AiErrorCode.AI_OUTPUT_SCHEMA_INVALID);
            auditService.recordFailure(request, prompt.version(),
                    AiErrorCode.AI_OUTPUT_SCHEMA_INVALID,
                    "DSL validation errors: " + validation.getErrors());
        } else {
            auditService.recordSuccess(request, response, prompt.version());
        }

        // Audience preview only when validated (avoid SQL for invalid drafts)
        AudiencePreviewResult preview = null;
        if (validation.isValid()) {
            try {
                preview = audiencePreviewService.preview(dsl);
            } catch (Exception e) {
                log.warn("Audience preview failed for requestId={}: {}", requestId, e.getMessage());
                preview = AudiencePreviewResult.builder()
                        .estimatedCount(0)
                        .calculationMode("SNAPSHOT")
                        .warnings(java.util.List.of("preview failed: " + e.getMessage()))
                        .build();
            }
        }

        var draft = draftService.saveGeneratedDraft(requestId, operatorId, text, dsl, validation, preview);
        return new ParseResult(requestId, draft.getId(), draft.getValidationStatus(),
                dsl, preview, validation.getMissingFields(), validation.getWarnings());
    }

    private int safeInt(Integer i) { return i == null ? 0 : i; }

    /**
     * Returned by {@link #parse}.
     */
    public record ParseResult(
            String requestId,
            Long draftId,
            String status,
            CampaignDsl dsl,
            AudiencePreviewResult estimatedAudience,
            java.util.List<String> missingFields,
            java.util.List<String> warnings
    ) {}
}
