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
 * Builds the Content prompt. Promotion facts are taken from the DSL draft
 * (server-side) and injected as authoritative facts; the model is told NOT
 * to invent or modify them.
 */
@Slf4j
@Component
public class CampaignContentPromptBuilder {

    private static final String TEMPLATE_PATH = "prompts/campaign-content-v1.md";

    public BuiltPrompt build(Map<String, Object> input) {
        String system = loadTemplate();
        String user = "Content input (JSON):\n" + JsonUtil.toJson(input);
        return new BuiltPrompt(system, user, PromptVersion.CAMPAIGN_CONTENT);
    }

    private String loadTemplate() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(TEMPLATE_PATH).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Generate three differentiated content variants. Output JSON only.";
        }
    }

    public record BuiltPrompt(String systemPrompt, String userPrompt, String version) {}
}
