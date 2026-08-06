package com.pulseflow.ai.support;

/**
 * Stable error codes for the AI Copilot layer.
 *
 * <p>Codes are grouped:</p>
 * <ul>
 *   <li>{@code AI_DISABLED} — feature switch off, never retried by client.</li>
 *   <li>{@code AI_PROVIDER_*} — upstream LLM provider failures (timeout / 5xx / rate limit).</li>
 *   <li>{@code AI_EMPTY_RESPONSE} / {@code AI_INVALID_JSON} / {@code AI_OUTPUT_SCHEMA_INVALID} — parsing layer.</li>
 *   <li>{@code AI_UNKNOWN_FIELD} / {@code AI_INVALID_OPERATOR} / {@code AI_INVALID_VALUE} / {@code AI_MISSING_REQUIRED_FACT} / {@code AI_CONTENT_FACT_CONFLICT} — business guardrail.</li>
 *   <li>{@code AI_AUDIENCE_PREVIEW_FAILED} — downstream audience preview failure.</li>
 *   <li>{@code AI_REVIEW_DATA_NOT_READY} — performance summary not computed yet.</li>
 *   <li>{@code AI_INTERNAL_ERROR} — fallback for unexpected failures.</li>
 * </ul>
 */
public final class AiErrorCode {

    public static final String AI_DISABLED                  = "AI_DISABLED";
    public static final String AI_FORBIDDEN                 = "AI_FORBIDDEN";
    public static final String AI_PROVIDER_TIMEOUT          = "AI_PROVIDER_TIMEOUT";
    public static final String AI_PROVIDER_UNAVAILABLE      = "AI_PROVIDER_UNAVAILABLE";
    public static final String AI_PROVIDER_RATE_LIMITED     = "AI_PROVIDER_RATE_LIMITED";
    public static final String AI_EMPTY_RESPONSE            = "AI_EMPTY_RESPONSE";
    public static final String AI_INVALID_JSON              = "AI_INVALID_JSON";
    public static final String AI_OUTPUT_SCHEMA_INVALID     = "AI_OUTPUT_SCHEMA_INVALID";
    public static final String AI_UNKNOWN_FIELD             = "AI_UNKNOWN_FIELD";
    public static final String AI_INVALID_OPERATOR          = "AI_INVALID_OPERATOR";
    public static final String AI_INVALID_VALUE             = "AI_INVALID_VALUE";
    public static final String AI_MISSING_REQUIRED_FACT     = "AI_MISSING_REQUIRED_FACT";
    public static final String AI_CONTENT_FACT_CONFLICT     = "AI_CONTENT_FACT_CONFLICT";
    public static final String AI_AUDIENCE_PREVIEW_FAILED   = "AI_AUDIENCE_PREVIEW_FAILED";
    public static final String AI_REVIEW_DATA_NOT_READY     = "AI_REVIEW_DATA_NOT_READY";
    public static final String AI_EVIDENCE_INVALID          = "AI_EVIDENCE_INVALID";
    public static final String AI_INTERNAL_ERROR            = "AI_INTERNAL_ERROR";

    private AiErrorCode() {
        throw new UnsupportedOperationException("Constant class");
    }
}
