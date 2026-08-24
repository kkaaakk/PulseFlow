package com.pulseflow.ai.guardrail;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable, provider-neutral result of one PII detection request. */
public record PiiDetectionResult(
        boolean hasPii,
        List<PiiDetectionEntity> entities,
        String redactedText,
        String provider
) {
    public PiiDetectionResult {
        entities = entities == null ? List.of() : List.copyOf(entities);
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        hasPii = hasPii || !entities.isEmpty();
    }

    public static PiiDetectionResult clean(String provider) {
        return new PiiDetectionResult(false, List.of(), null, provider);
    }

    /** Safe category-only view for client responses, metrics, and logs. */
    public Set<String> categories() {
        Set<String> categories = new LinkedHashSet<>();
        for (PiiDetectionEntity entity : entities) {
            categories.add(entity.category());
        }
        if (categories.isEmpty() && hasPii) {
            categories.add("Unknown");
        }
        return Set.copyOf(categories);
    }
}
