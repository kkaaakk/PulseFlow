package com.pulseflow.ai.guardrail;

import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.ai.support.AiErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards what may be sent to the LLM.
 *
 * <p>Per design §3.4:</p>
 * <ul>
 *   <li>Allowed: aggregated counts, rates, averages, tag ratios, historical means.</li>
 *   <li>Forbidden: userId, mobile, email, address, ID numbers, raw behaviour logs,
 *       order details, device ids, unmasked operational data.</li>
 * </ul>
 *
 * <p>This validator runs on every prompt input. It blocks the call BEFORE the
 * HTTP request is dispatched, so we never leak PII even on misconfiguration.</p>
 */
@Component
public class SensitiveDataSanitizer {

    /** Keys whose presence in a user prompt input map is a hard block. */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "userId", "userIds", "mobile", "phone", "email", "address",
            "idCard", "idNumber", "deviceId", "imei", "rawEvents",
            "orderDetails", "behaviourLogs", "fullName", "realName"
    );

    /** Patterns that, if matched in free text, block the call. */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("1[3-9]\\d{9}"),                       // CN mobile
            Pattern.compile("\\b\\d{15,18}[0-9Xx]\\b"),            // ID card
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")        // email
    );

    /**
     * Inspect a structured input map. Throws {@link PulseFlowException} on
     * any forbidden key or pattern match.
     */
    public void inspect(Map<String, Object> input) {
        if (input == null) return;
        for (String key : input.keySet()) {
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new PulseFlowException(AiErrorCode.AI_INTERNAL_ERROR,
                        "Sensitive key '" + key + "' is not allowed in AI input");
            }
        }
        for (Object value : input.values()) {
            if (value instanceof CharSequence cs) {
                String text = cs.toString();
                for (Pattern p : FORBIDDEN_PATTERNS) {
                    if (p.matcher(text).find()) {
                        throw new PulseFlowException(AiErrorCode.AI_INTERNAL_ERROR,
                                "Sensitive pattern matched in AI input value");
                    }
                }
            }
        }
    }

    /**
     * Inspect free-form text (e.g. the natural-language intent input from
     * /parse). Returns the text if clean; throws otherwise.
     */
    public String inspectText(String text) {
        if (text == null) return null;
        for (Pattern p : FORBIDDEN_PATTERNS) {
            if (p.matcher(text).find()) {
                throw new PulseFlowException(AiErrorCode.AI_INTERNAL_ERROR,
                        "Sensitive pattern matched in AI input text");
            }
        }
        return text;
    }
}
