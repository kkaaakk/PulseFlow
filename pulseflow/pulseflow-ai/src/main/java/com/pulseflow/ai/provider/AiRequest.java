package com.pulseflow.ai.provider;

import com.pulseflow.ai.support.AiTaskType;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Immutable request to an {@link AiModelClient}.
 *
 * <p>Field contract:</p>
 * <ul>
 *   <li>{@code requestId} — client-generated, used as UK in {@code ai_generation_record}.</li>
 *   <li>{@code taskType} — drives Fake fixture dispatch and observability tags.</li>
 *   <li>{@code responseSchemaName} — logical name of the expected JSON schema
 *       (informational for v1; providers may use it for response_format in future).</li>
 *   <li>{@code systemPrompt} / {@code userPrompt} — never contain user-level PII.</li>
 *   <li>{@code metadata} — operatorId / draftId / campaignId for audit correlation.</li>
 * </ul>
 */
@Data
@Builder
public class AiRequest {

    private String requestId;
    private AiTaskType taskType;
    private String systemPrompt;
    private String userPrompt;
    private String responseSchemaName;

    @Builder.Default
    private double temperature = 0.2;

    @Builder.Default
    private int maxTokens = 2048;

    @Builder.Default
    private Map<String, Object> metadata = Map.of();
}
