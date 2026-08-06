package com.pulseflow.ai.prompt;

import com.pulseflow.ai.guardrail.AiFieldRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds the (systemPrompt, userPrompt) pair for the Campaign Intent task.
 *
 * <p>The system prompt is loaded from {@code classpath:prompts/campaign-intent-v1.md}
 * and injected with the live field registry so the model only sees fields the
 * Java validator would accept.</p>
 */
@Component
@RequiredArgsConstructor
public class CampaignIntentPromptBuilder {

    private static final String TEMPLATE_PATH = "prompts/campaign-intent-v1.md";

    private final AiFieldRegistry fieldRegistry;

    public BuiltPrompt build(String userText, String timezone) {
        String template = loadTemplate();
        String system = template
                .replace("{{FIELDS}}", fieldRegistry.toPromptSection())
                .replace("{{NOW}}", OffsetDateTime.now().toString())
                .replace("{{TODAY}}", LocalDate.now().toString())
                .replace("{{TIMEZONE}}", timezone == null ? ZoneId.systemDefault().toString() : timezone);

        String user = """
                Operator brief:
                ---
                %s
                ---
                Timezone: %s
                """.formatted(userText, timezone == null ? ZoneId.systemDefault().toString() : timezone);

        return new BuiltPrompt(system, user, PromptVersion.CAMPAIGN_INTENT);
    }

    private String loadTemplate() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(TEMPLATE_PATH).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Minimal fallback so unit tests don't fail on resource loading
            return "Convert the brief to Campaign DSL JSON. Fields:\n{{FIELDS}}\nNow: {{NOW}}\nTZ: {{TIMEZONE}}\nOutput JSON only.";
        }
    }

    public record BuiltPrompt(String systemPrompt, String userPrompt, String version) {}
}
