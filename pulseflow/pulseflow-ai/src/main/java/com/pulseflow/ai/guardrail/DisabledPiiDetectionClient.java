package com.pulseflow.ai.guardrail;

/**
 * No-op provider used when the optional Azure PII feature is disabled.
 * PulseFlow's local business-field guardrail is still enforced by the
 * {@link SensitiveDataSanitizer}.
 */
public final class DisabledPiiDetectionClient implements PiiDetectionClient {

    public static final String PROVIDER_NAME = "disabled";

    @Override
    public PiiDetectionResult detect(String text) {
        return PiiDetectionResult.clean(PROVIDER_NAME);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}
