package com.pulseflow.ai.guardrail;

/**
 * Provider-neutral natural-language PII detection boundary.
 *
 * <p>The sanitizer owns PulseFlow's business-field policy; implementations of
 * this interface only answer whether free-form text contains personal data.
 * Implementations must fail closed by throwing an exception when the provider
 * cannot complete detection.</p>
 */
@FunctionalInterface
public interface PiiDetectionClient {

    /**
     * Detect PII in the supplied text.
     *
     * @param text non-blank text; callers may skip empty values
     * @return provider result; never {@code null}
     */
    PiiDetectionResult detect(String text);

    /** Provider label used for metrics and safe audit diagnostics. */
    default String providerName() {
        return "unknown";
    }
}
