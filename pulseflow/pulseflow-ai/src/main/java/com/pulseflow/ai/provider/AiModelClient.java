package com.pulseflow.ai.provider;

/**
 * Unified client for structured LLM calls.
 *
 * <p>All AI features in PulseFlow go through this interface so that:</p>
 * <ul>
 *   <li>Providers can be swapped (OpenAI-compatible, Fake, future vendors).</li>
 *   <li>{@link com.pulseflow.ai.infrastructure.observability.AiAuditService}
 *       can record every call uniformly.</li>
 *   <li>Retries / timeouts are centralised.</li>
 * </ul>
 *
 * <p>Implementations MUST:</p>
 * <ul>
 *   <li>Throw {@link com.pulseflow.ai.support.AiProviderException} on upstream
 *       timeout / 5xx / rate-limit (after exhausting {@code maxRetries}).</li>
 *   <li>Never throw on parseable output — parsing is the caller's responsibility
 *       via {@link com.pulseflow.ai.guardrail.AiOutputParser}.</li>
 *   <li>Return a response with {@code rawContent} even when the model returns
 *       non-JSON, so the audit log captures what happened.</li>
 * </ul>
 */
public interface AiModelClient {

    AiResponse generateStructured(AiRequest request);

    /**
     * Whether this client is ready to serve requests.
     * FakeAiModelClient returns true; OpenAiCompatibleClient returns true only
     * when baseUrl + apiKey + model are all configured.
     */
    default boolean isAvailable() {
        return true;
    }

    /** Provider id written to ai_generation_record.provider. */
    String providerName();
}
