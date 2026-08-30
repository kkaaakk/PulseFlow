package com.pulseflow.ai.infrastructure.config;

import com.pulseflow.ai.guardrail.DisabledPiiDetectionClient;
import com.pulseflow.ai.guardrail.FakePiiDetectionClient;
import com.pulseflow.ai.guardrail.PiiDetectionClient;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AzurePiiDetectionClient;
import com.pulseflow.ai.provider.FakeAiModelClient;
import com.pulseflow.ai.provider.OpenAiCompatibleClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up the AI Copilot layer.
 *
 * <p>Beans under {@code com.pulseflow.ai} are only scanned when the feature
 * is enabled, so the entire AI layer can be turned off with zero runtime
 * impact on the core Campaign engine.</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiFeatureProperties.class)
@ConditionalOnProperty(prefix = "pulseflow.ai", name = "enabled", havingValue = "true")
@ComponentScan(basePackages = "com.pulseflow.ai")
@MapperScan("com.pulseflow.ai.infrastructure.persistence.mapper")
public class AiAutoConfiguration {

    private final AiFeatureProperties properties;

    public AiAutoConfiguration(AiFeatureProperties properties) {
        this.properties = properties;
    }

    /**
     * A real LLM must never start with the PII guardrail disabled. This check
     * is deliberately independent of the conditional PII client bean so a
     * custom PiiDetectionClient cannot accidentally bypass the startup policy.
     */
    @PostConstruct
    void validateRealAiRequiresPiiGuardrail() {
        AiFeatureProperties.Pii pii = properties.getPii();
        if (!properties.isMockEnabled() && (pii == null || !pii.isEnabled())) {
            throw new IllegalStateException("Real AI provider requires PII guardrail to be enabled");
        }
    }

    @Bean
    @ConditionalOnMissingBean(AiModelClient.class)
    public AiModelClient aiModelClient(AiFeatureProperties properties) {
        if (properties.isMockEnabled()) {
            log.info("AI Copilot using FakeAiModelClient (mock-enabled=true)");
            return new FakeAiModelClient();
        }
        log.info("AI Copilot using OpenAiCompatibleClient (provider={}, model={})",
                properties.getProvider(), properties.getModel());
        return new OpenAiCompatibleClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean(PiiDetectionClient.class)
    public PiiDetectionClient piiDetectionClient(AiFeatureProperties properties) {
        AiFeatureProperties.Pii pii = properties.getPii();
        if (pii == null || !pii.isEnabled()) {
            log.info("AI PII guardrail disabled; local business-field guardrail remains active");
            return new DisabledPiiDetectionClient();
        }

        // The existing global mock switch is the CI/local contract. The nested
        // switch is useful when only the PII provider should be stubbed.
        if (properties.isMockEnabled()) {
            log.info("AI PII guardrail using FakePiiDetectionClient (mock mode)");
            return new FakePiiDetectionClient();
        }

        validateAzurePiiConfiguration(pii);
        log.info("AI PII guardrail using Azure AI Language (language={}, timeoutSeconds={})",
                pii.getLanguage(), pii.getTimeoutSeconds());
        return new AzurePiiDetectionClient(
                pii.getEndpoint(), pii.getApiKey(), pii.getLanguage(), pii.getTimeoutSeconds());
    }

    private void validateAzurePiiConfiguration(AiFeatureProperties.Pii pii) {
        if (isBlank(pii.getEndpoint()) || isBlank(pii.getApiKey())) {
            throw new IllegalStateException(
                    "pulseflow.ai.pii.enabled=true requires AZURE_LANGUAGE_ENDPOINT and AZURE_LANGUAGE_KEY"
                            + " when mock mode is disabled");
        }
        if (isBlank(pii.getLanguage()) || !pii.getLanguage().matches("[A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?")) {
            throw new IllegalStateException(
                    "pulseflow.ai.pii.language must be a valid language code when Azure PII is enabled");
        }
        if (pii.getTimeoutSeconds() <= 0) {
            throw new IllegalStateException(
                    "pulseflow.ai.pii.timeout-seconds must be greater than zero");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
