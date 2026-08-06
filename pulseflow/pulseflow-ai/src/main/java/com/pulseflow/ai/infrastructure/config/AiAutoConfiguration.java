package com.pulseflow.ai.infrastructure.config;

import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.FakeAiModelClient;
import com.pulseflow.ai.provider.OpenAiCompatibleClient;
import lombok.extern.slf4j.Slf4j;
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
public class AiAutoConfiguration {

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
}
