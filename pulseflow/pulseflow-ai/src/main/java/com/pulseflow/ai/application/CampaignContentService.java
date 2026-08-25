package com.pulseflow.ai.application;

import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.PromotionFact;
import com.pulseflow.ai.domain.content.ContentResult;
import com.pulseflow.ai.guardrail.AiOutputParser;
import com.pulseflow.ai.guardrail.ContentFactValidator;
import com.pulseflow.ai.guardrail.SensitiveDataSanitizer;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AiRequest;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.prompt.CampaignContentPromptBuilder;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.ai.support.AiTaskType;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Campaign Content pipeline.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Load draft DSL — promotion facts are taken from the draft only,
 *       front-end cannot override them</li>
 *   <li>Sanitise tone / free-text inputs</li>
 *   <li>Build a constrained input map (objective, channel, audienceSummary,
 *       promotionFacts, tone, length limits, forbiddenWords, validUntil)</li>
 *   <li>Build prompt → call {@link AiModelClient}</li>
 *   <li>Parse JSON → {@link ContentResult}</li>
 *   <li>{@link ContentFactValidator}: length / forbidden / template / numbers /
 *       PII / fabricated-urgency checks</li>
 *   <li>Audit + metrics</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignContentService {

    private static final int DEFAULT_TITLE_MAX = 24;
    private static final int DEFAULT_BODY_MAX = 80;

    private final CampaignAiDraftService draftService;
    private final CampaignContentPromptBuilder promptBuilder;
    private final AiModelClient aiModelClient;
    private final AiOutputParser outputParser;
    private final ContentFactValidator factValidator;
    private final SensitiveDataSanitizer sanitizer;
    private final AiAuditService auditService;
    private final AiMetrics aiMetrics;

    public ContentOutput generate(Long draftId, Long operatorId, String tone,
                                   Integer titleMaxLength, Integer bodyMaxLength,
                                   Integer variantCount, List<String> forbiddenWords,
                                   String audienceSummary) {
        // 1. Load DSL — promotion facts must come from the draft, not the client
        CampaignDsl dsl = draftService.loadDsl(draftId);
        if (dsl.getPromotionFacts() == null || dsl.getPromotionFacts().isEmpty()) {
            throw new AiOutputInvalidException(AiErrorCode.AI_OUTPUT_SCHEMA_INVALID,
                    "Draft " + draftId + " has no promotionFacts; cannot generate content");
        }

        // 2. Build the exact constrained input that will become the prompt,
        //    then run both business-field and natural-language PII checks over
        //    that input before the prompt builder or model is reached.
        Map<String, Object> input = buildInput(dsl, tone, titleMaxLength, bodyMaxLength,
                variantCount, forbiddenWords, audienceSummary);
        sanitizer.inspect(input);
        String inputJson = JsonUtil.toJson(input);

        // 3. Prompt + call
        CampaignContentPromptBuilder.BuiltPrompt prompt = promptBuilder.build(input);
        String requestId = "ai_req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        AiRequest request = AiRequest.builder()
                .requestId(requestId)
                .taskType(AiTaskType.CONTENT)
                .systemPrompt(prompt.systemPrompt())
                .userPrompt(prompt.userPrompt())
                .responseSchemaName("ContentResult")
                .temperature(0.4)
                .maxTokens(2048)
                .metadata(Map.of(
                        "operatorId", String.valueOf(operatorId),
                        "draftId", String.valueOf(draftId)))
                .build();

        long started = System.currentTimeMillis();
        AiResponse response;
        try {
            response = aiModelClient.generateStructured(request);
        } catch (AiProviderException e) {
            aiMetrics.recordFailure(AiTaskType.CONTENT, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            throw e;
        }
        aiMetrics.recordRequest(AiTaskType.CONTENT, response.getProvider(),
                Duration.ofMillis(System.currentTimeMillis() - started), true);
        aiMetrics.recordTokens(AiTaskType.CONTENT, response.getProvider(),
                safeInt(response.getPromptTokens()), safeInt(response.getCompletionTokens()));

        // 5. Parse + fact validate
        ContentResult result;
        try {
            result = outputParser.parseObject(response.getRawContent(), ContentResult.class);
            result = factValidator.validate(input, result);
        } catch (AiOutputInvalidException e) {
            aiMetrics.recordFailure(AiTaskType.CONTENT, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            throw e;
        }

        auditService.recordSuccess(request, response, prompt.version());
        return new ContentOutput(result, inputJson, requestId);
    }

    private Map<String, Object> buildInput(CampaignDsl dsl, String tone,
                                            Integer titleMaxLength, Integer bodyMaxLength,
                                            Integer variantCount, List<String> forbiddenWords,
                                            String audienceSummary) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("objective", dsl.getObjective());
        input.put("channel", dsl.getChannel());
        input.put("campaignName", dsl.getCampaignName());
        input.put("audienceSummary", audienceSummary == null ? "" : audienceSummary);
        input.put("tone", tone == null ? "友好直接" : tone);
        input.put("titleMaxLength", titleMaxLength == null ? DEFAULT_TITLE_MAX : titleMaxLength);
        input.put("bodyMaxLength", bodyMaxLength == null ? DEFAULT_BODY_MAX : bodyMaxLength);
        input.put("variantCount", variantCount == null ? 3 : variantCount);

        // Promotion facts — server-authoritative
        List<Object> facts = new ArrayList<>();
        for (PromotionFact pf : dsl.getPromotionFacts()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", pf.getType());
            if (pf.getThreshold() != null) f.put("threshold", pf.getThreshold());
            if (pf.getDiscount() != null) f.put("discount", pf.getDiscount());
            if (pf.getRate() != null) f.put("rate", pf.getRate());
            if (pf.getValidUntil() != null) f.put("validUntil", pf.getValidUntil());
            if (pf.getDescription() != null) f.put("description", pf.getDescription());
            facts.add(f);
        }
        input.put("promotionFacts", facts);

        // Also surface validUntil at top level so the validator can allow URGENCY copy
        // to mention the real deadline.
        if (!dsl.getPromotionFacts().isEmpty()
                && dsl.getPromotionFacts().get(0).getValidUntil() != null) {
            input.put("validUntil", dsl.getPromotionFacts().get(0).getValidUntil());
        }

        if (forbiddenWords != null && !forbiddenWords.isEmpty()) {
            input.put("forbiddenWords", forbiddenWords);
        }
        return input;
    }

    private int safeInt(Integer i) { return i == null ? 0 : i; }

    /**
     * Returned by {@link #generate}.
     */
    public record ContentOutput(
            ContentResult content,
            String inputJson,
            String requestId
    ) {}
}
