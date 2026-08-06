package com.pulseflow.ai.provider;

import lombok.Builder;
import lombok.Data;

/**
 * Response from an {@link AiModelClient}.
 *
 * <p>{@code structuredContent} is the JSON string extracted from the model's
 * raw output (markdown fences stripped). Downstream services parse it into
 * domain objects via {@link com.pulseflow.ai.guardrail.AiOutputParser}.</p>
 */
@Data
@Builder
public class AiResponse {

    private String requestId;
    private String provider;
    private String model;

    /** Raw model text, may contain markdown code fences. */
    private String rawContent;

    /** Cleaned JSON string ready for domain parsing. May be null on empty response. */
    private String structuredContent;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private long latencyMs;
    private String finishReason;
}
