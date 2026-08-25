package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.support.AiPiiGuardrailUnavailableException;

import java.util.Arrays;
import java.util.List;

/**
 * Deterministic PII provider for local development, unit tests, and CI.
 * It never performs network I/O and can model clean, blocked, timeout, and
 * provider-failure outcomes.
 */
public final class FakePiiDetectionClient implements PiiDetectionClient {

    public static final String PROVIDER_NAME = "fake-pii";

    public enum Behavior {
        CLEAN,
        PII_DETECTED,
        TIMEOUT,
        PROVIDER_FAILURE
    }

    private final Behavior behavior;
    private final List<String> categories;

    public FakePiiDetectionClient() {
        this(Behavior.CLEAN, List.of("PhoneNumber"));
    }

    public FakePiiDetectionClient(Behavior behavior, String... categories) {
        this(behavior, Arrays.asList(categories));
    }

    public FakePiiDetectionClient(Behavior behavior, List<String> categories) {
        this.behavior = behavior == null ? Behavior.CLEAN : behavior;
        this.categories = categories == null || categories.isEmpty()
                ? List.of("PhoneNumber")
                : List.copyOf(categories);
    }

    @Override
    public PiiDetectionResult detect(String text) {
        return switch (behavior) {
            case CLEAN -> PiiDetectionResult.clean(PROVIDER_NAME);
            case PII_DETECTED -> new PiiDetectionResult(
                    true,
                    categories.stream()
                            .map(category -> new PiiDetectionEntity(category, null, 0.99d, -1, 0))
                            .toList(),
                    null,
                    PROVIDER_NAME);
            case TIMEOUT, PROVIDER_FAILURE -> throw new AiPiiGuardrailUnavailableException(
                    "PII guardrail provider is unavailable");
        };
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}
