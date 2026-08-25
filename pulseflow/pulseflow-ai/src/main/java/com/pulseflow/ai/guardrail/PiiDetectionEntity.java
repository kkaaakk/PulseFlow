package com.pulseflow.ai.guardrail;

/**
 * A single PII entity returned by a detection provider.
 *
 * <p>{@code text} is retained only for provider-neutral internal processing
 * and tests. It must never be logged, returned to a client, or persisted in an
 * AI audit record.</p>
 */
public record PiiDetectionEntity(
        String category,
        String text,
        double confidenceScore,
        int offset,
        int length
) {
    public PiiDetectionEntity {
        category = category == null || category.isBlank() ? "Unknown" : category;
        confidenceScore = Math.max(0.0d, Math.min(1.0d, confidenceScore));
        offset = Math.max(-1, offset);
        length = Math.max(0, length);
    }
}
