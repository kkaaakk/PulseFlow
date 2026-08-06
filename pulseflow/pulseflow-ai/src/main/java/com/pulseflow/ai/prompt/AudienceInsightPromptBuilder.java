package com.pulseflow.ai.prompt;

import com.pulseflow.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Builds the Insight prompt. The user prompt contains ONLY aggregated metrics,
 * never individual user rows.
 */
@Slf4j
@Component
public class AudienceInsightPromptBuilder {

    private static final String TEMPLATE_PATH = "prompts/audience-insight-v1.md";

    public BuiltPrompt build(Map<String, Object> aggregatedInput) {
        String system = loadTemplate();
        String user = "Audience aggregate input (JSON):\n" + JsonUtil.toJson(aggregatedInput);
        return new BuiltPrompt(system, user, PromptVersion.AUDIENCE_INSIGHT);
    }

    private String loadTemplate() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(TEMPLATE_PATH).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Interpret the audience aggregate metrics. Output JSON only. Every finding MUST include evidenceKeys.";
        }
    }

    public record BuiltPrompt(String systemPrompt, String userPrompt, String version) {}
}
