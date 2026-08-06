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
 * Builds the Review prompt. The user prompt contains ONLY pre-computed
 * campaign metrics and historical baselines.
 */
@Slf4j
@Component
public class CampaignReviewPromptBuilder {

    private static final String TEMPLATE_PATH = "prompts/campaign-review-v1.md";

    public BuiltPrompt build(Map<String, Object> input) {
        String system = loadTemplate();
        String user = "Campaign performance input (JSON):\n" + JsonUtil.toJson(input);
        return new BuiltPrompt(system, user, PromptVersion.CAMPAIGN_REVIEW);
    }

    private String loadTemplate() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(TEMPLATE_PATH).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Interpret the campaign performance metrics. Output JSON only. Every entry MUST include evidenceKeys.";
        }
    }

    public record BuiltPrompt(String systemPrompt, String userPrompt, String version) {}
}
