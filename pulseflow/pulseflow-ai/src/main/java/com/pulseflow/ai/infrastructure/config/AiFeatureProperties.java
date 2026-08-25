package com.pulseflow.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Copilot feature configuration under {@code pulseflow.ai.*}.
 *
 * <p>Security rules:</p>
 * <ul>
 *   <li>{@code api-key} MUST come from env var {@code PULSEFLOW_AI_API_KEY}; never committed.</li>
 *   <li>{@code enabled=false} → AI beans are not assembled and all AI endpoints return {@code AI_DISABLED}.</li>
 *   <li>{@code mock-enabled=true} → {@link com.pulseflow.ai.provider.FakeAiModelClient} is used
 *       regardless of {@code provider}; for CI and local demo without API key.</li>
 *   <li>Azure PII credentials are held in the nested {@code pii} configuration and are
 *       never persisted to AI audit records.</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "pulseflow.ai")
public class AiFeatureProperties {

    /** Master switch. When false, AI endpoints return AI_DISABLED. */
    private boolean enabled = false;

    /** Provider id; only "openai-compatible" is implemented in v1. */
    private String provider = "openai-compatible";

    /** Base URL of the OpenAI-compatible endpoint (e.g. DeepSeek). */
    private String baseUrl = "";

    /** API key. Should be injected via ${PULSEFLOW_AI_API_KEY}. */
    private String apiKey = "";

    /** Model name passed to the provider. */
    private String model = "";

    /** Request timeout in seconds. */
    private int timeoutSeconds = 30;

    /** Max retries on timeout/5xx. Hard limit to control cost. */
    private int maxRetries = 1;

    /** When true, FakeAiModelClient is used (CI / local demo). */
    private boolean mockEnabled = true;

    /** Draft expiry in hours (campaign_ai_draft.expires_at = createdAt + this). */
    private int draftTtlHours = 24;

    /** Audience preview hard cap to bound preview SQL cost. */
    private int audiencePreviewLimit = 100_000;

    /** Azure AI Language PII guardrail configuration. */
    private Pii pii = new Pii();

    /** Review pipeline configuration (lock, retry, cooldown). */
    private Review review = new Review();

    @Data
    public static class Review {
        /** Minutes before a PROCESSING lock is considered stale and can be stolen. */
        private int lockStaleMinutes = 10;

        /** Max AI retry attempts before a review transitions to PERMANENT_FAILED. */
        private int maxRetryCount = 3;

        /** Cooldown (seconds) for the manual /regenerate endpoint to prevent abuse. */
        private int regenerateCooldownSeconds = 60;

        /**
         * Minutes to wait before treating sentCount=0 as "data not ready"
         * (retryable) vs permanently insufficient. During this grace window
         * the review is marked DATA_NOT_READY and the job will retry after
         * the delay, giving consumption pipelines time to finish aggregating.
         */
        private int dataReadyDelayMinutes = 10;
    }

    @Data
    public static class Pii {
        /** Enables the Azure-backed natural-language PII guardrail. */
        private boolean enabled = false;

        /** Azure AI Language endpoint; injected from AZURE_LANGUAGE_ENDPOINT. */
        private String endpoint = "";

        /** Azure AI Language API key; injected from AZURE_LANGUAGE_KEY. */
        private String apiKey = "";

        /** Text PII language code. Azure supports zh-hans (and zh) for Simplified Chinese. */
        private String language = "zh-hans";

        /** HTTP timeout for connect/read/write/response operations. */
        private int timeoutSeconds = 5;

        /** Explicit PII fake mode for tests; global AI mock mode also selects the fake. */
        private boolean mockEnabled = false;
    }
}
