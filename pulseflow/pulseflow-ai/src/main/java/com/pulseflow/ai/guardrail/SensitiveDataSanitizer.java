package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.support.AiPiiGuardrailUnavailableException;
import com.pulseflow.ai.support.AiSensitiveDataDetectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Two-layer guardrail for data sent to an LLM.
 *
 * <p>Layer one is PulseFlow's local business policy. It blocks fields such as
 * {@code userId}, {@code rawEvents}, and {@code orderDetails} even when those
 * values would not be classified as personal data by an external provider.
 * Layer two delegates free-form natural-language detection to the configured
 * {@link PiiDetectionClient}. Detected PII is blocked, never redacted and sent
 * onward.</p>
 *
 * <p>Provider failures are fail-closed: an unavailable PII service stops the
 * AI request instead of allowing unchecked input to reach the LLM.</p>
 */
@Component
public class SensitiveDataSanitizer {

    /** Keys whose presence in a user prompt input map is a hard block. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "userId", "userIds", "mobile", "phone", "email", "address",
            "idCard", "idNumber", "deviceId", "imei", "rawEvents",
            "orderDetails", "behaviourLogs", "fullName", "realName"
    );

    /** Longest first so userIds wins over the shorter userId token. */
    private static final List<String> FORBIDDEN_IDENTIFIER_ORDER = FORBIDDEN_KEYS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private final PiiDetectionClient piiDetectionClient;
    private final AiMetrics metrics;

    /** Spring uses this constructor so metrics remain best-effort. */
    @Autowired
    public SensitiveDataSanitizer(PiiDetectionClient piiDetectionClient, AiMetrics metrics) {
        this.piiDetectionClient = piiDetectionClient;
        this.metrics = metrics;
    }

    /** Convenient constructor for pure unit tests without a Spring context. */
    public SensitiveDataSanitizer(PiiDetectionClient piiDetectionClient) {
        this(piiDetectionClient, null);
    }

    /**
     * Inspect a structured input map. Business field checks run before any
     * provider call; textual leaves are then checked by the configured PII
     * client.
     */
    public void inspect(Map<String, Object> input) {
        if (input == null) return;
        List<String> textValues = new ArrayList<>();
        collectValues(input, textValues);
        for (String text : textValues) {
            inspectText(text);
        }
    }

    /**
     * Inspect free-form text. The original text is returned only when the
     * guardrail is clean; no redacted text is sent to the LLM in this phase.
     */
    public String inspectText(String text) {
        if (text == null || text.isBlank()) return text;

        String forbiddenIdentifier = findForbiddenBusinessIdentifier(text);
        if (forbiddenIdentifier != null) {
            throw new AiSensitiveDataDetectedException(
                    Set.of("BUSINESS_FIELD:" + forbiddenIdentifier));
        }

        long started = System.nanoTime();
        PiiDetectionResult result;
        try {
            result = piiDetectionClient.detect(text);
            if (result == null) {
                throw new AiPiiGuardrailUnavailableException("PII provider returned no result");
            }
        } catch (AiPiiGuardrailUnavailableException e) {
            recordPiiDetection("unknown", started, "failure");
            throw e;
        } catch (RuntimeException e) {
            // Any unexpected provider/SDK failure is treated as unavailable.
            // Do not include provider exception text because it can contain
            // request details or sensitive content.
            recordPiiDetection(piiDetectionClient.providerName(), started, "failure");
            throw new AiPiiGuardrailUnavailableException(
                    "PII provider failed", e);
        }

        String provider = result.provider();
        recordPiiDetection(provider, started, result.hasPii() ? "blocked" : "clean");
        if (result.hasPii()) {
            // Only provider-supplied categories leave this boundary. Entity
            // text is deliberately never copied into the exception message.
            throw new AiSensitiveDataDetectedException(result.categories());
        }
        return text;
    }

    private void collectValues(Object value, Collection<String> textValues) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                String forbiddenKey = findForbiddenBusinessKey(key);
                if (forbiddenKey != null) {
                    throw new AiSensitiveDataDetectedException(Set.of("BUSINESS_FIELD:" + forbiddenKey));
                }
                collectValues(entry.getValue(), textValues);
            }
            return;
        }
        if (value instanceof CharSequence sequence) {
            textValues.add(sequence.toString());
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectValues(item, textValues);
            }
            return;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectValues(java.lang.reflect.Array.get(value, i), textValues);
            }
        }
    }

    /**
     * Finds an explicitly named PulseFlow business field without inspecting or
     * logging the value that follows it. ASCII identifier boundaries prevent
     * accidental matches such as {@code customerUserIdAlias}, while Chinese
     * text can appear directly next to a business identifier.
     */
    private String findForbiddenBusinessIdentifier(String text) {
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String key : FORBIDDEN_IDENTIFIER_ORDER) {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            int fromIndex = 0;
            while (fromIndex < normalizedText.length()) {
                int index = normalizedText.indexOf(normalizedKey, fromIndex);
                if (index < 0) break;
                if (hasIdentifierBoundaries(normalizedText, index, normalizedKey.length())) {
                    return key;
                }
                fromIndex = index + normalizedKey.length();
            }
        }
        return null;
    }

    private String findForbiddenBusinessKey(String key) {
        for (String forbiddenKey : FORBIDDEN_KEYS) {
            if (forbiddenKey.equalsIgnoreCase(key)) return forbiddenKey;
        }
        return null;
    }

    private boolean hasIdentifierBoundaries(String text, int start, int length) {
        int end = start + length;
        return (start == 0 || !isAsciiIdentifierChar(text.charAt(start - 1)))
                && (end == text.length() || !isAsciiIdentifierChar(text.charAt(end)));
    }

    private boolean isAsciiIdentifierChar(char value) {
        return value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '_';
    }

    private void recordPiiDetection(String provider, long startedNanos, String result) {
        if (metrics == null) return;
        String safeProvider = provider == null || provider.isBlank() ? "unknown" : provider;
        metrics.recordPiiDetection(
                safeProvider,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)),
                result);
    }
}
