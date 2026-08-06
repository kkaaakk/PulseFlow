package com.pulseflow.ai.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.ai.domain.insight.Finding;
import com.pulseflow.ai.domain.insight.InsightResult;
import com.pulseflow.ai.domain.insight.StrategySuggestion;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that AI insight output only references real evidence keys and
 * real numbers from the input.
 *
 * <p>Checks:</p>
 * <ol>
 *   <li>Every evidenceKey must exist in the input JSON.</li>
 *   <li>Numbers mentioned in descriptions must match the input value
 *       (within rounding tolerance for percentages).</li>
 *   <li>If any finding has invalid evidence → drop the finding.</li>
 *   <li>If the summary references a number not in input → fail overall.</li>
 * </ol>
 */
@Slf4j
@Component
public class InsightEvidenceValidator {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?%?");
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    /**
     * @param inputJson the original aggregated input JSON (sent to the LLM)
     * @param result    parsed AI output
     * @return cleaned result with invalid findings dropped
     * @throws AiOutputInvalidException if the summary or a critical number is fabricated
     */
    public InsightResult validate(String inputJson, InsightResult result) {
        JsonNode input = com.pulseflow.common.util.JsonUtil.fromJson(inputJson, JsonNode.class);
        Set<String> validKeys = collectValidKeys(input);

        // Findings: drop invalid ones
        List<Finding> keptFindings = new ArrayList<>();
        for (Finding f : result.getFindings() == null ? List.<Finding>of() : result.getFindings()) {
            if (f.getEvidenceKeys() == null || f.getEvidenceKeys().isEmpty()) {
                log.warn("Insight finding '{}' dropped: no evidenceKeys", f.getTitle());
                continue;
            }
            if (!validKeys.containsAll(f.getEvidenceKeys())) {
                log.warn("Insight finding '{}' dropped: unknown evidenceKeys {}", f.getTitle(), f.getEvidenceKeys());
                continue;
            }
            if (!numbersExistInInput(f.getDescription(), input)) {
                log.warn("Insight finding '{}' dropped: description references numbers not in input", f.getTitle());
                continue;
            }
            keptFindings.add(f);
        }
        result.setFindings(keptFindings);

        // StrategySuggestions: drop invalid ones
        List<StrategySuggestion> keptSuggestions = new ArrayList<>();
        for (StrategySuggestion s : result.getStrategySuggestions() == null
                ? List.<StrategySuggestion>of() : result.getStrategySuggestions()) {
            if (s.getEvidenceKeys() == null
                    || !validKeys.containsAll(s.getEvidenceKeys())) {
                log.warn("Insight suggestion '{}' dropped: invalid evidenceKeys", s.getSuggestion());
                continue;
            }
            if (!numbersExistInInput(s.getReason(), input)) {
                log.warn("Insight suggestion '{}' dropped: reason references numbers not in input", s.getSuggestion());
                continue;
            }
            keptSuggestions.add(s);
        }
        result.setStrategySuggestions(keptSuggestions);

        // Summary: must not invent numbers
        if (result.getSummary() != null && !numbersExistInInput(result.getSummary(), input)) {
            throw new AiOutputInvalidException(AiErrorCode.AI_EVIDENCE_INVALID,
                    "Insight summary references numbers not present in input: " + result.getSummary());
        }

        if (keptFindings.isEmpty() && keptSuggestions.isEmpty()) {
            // Soft-fail: keep summary but note that all findings were dropped.
            log.warn("Insight validation dropped all findings/suggestions");
        }
        return result;
    }

    /**
     * Collect all leaf paths from the input JSON, e.g.
     * "metrics.activeRate7d", "baseline.averageSpend30d", "topCategories".
     */
    private Set<String> collectValidKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        collectKeys(node, "", keys);
        // Also accept the umbrella key "contentVariants" used by reviews
        keys.add("contentVariants");
        return keys;
    }

    private void collectKeys(JsonNode node, String prefix, Set<String> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                out.add(path);
                collectKeys(e.getValue(), path, out);
            });
        } else if (node.isArray()) {
            // Accept the array path itself as evidence (e.g. "topCategories")
            out.add(prefix);
        }
    }

    /**
     * Check that every percentage / decimal in {@code text} can be matched
     * against some numeric value in the input (with rounding tolerance).
     */
    private boolean numbersExistInInput(String text, JsonNode input) {
        if (text == null || text.isBlank()) return true;
        Set<BigDecimal> inputNumbers = collectAllNumbers(input);
        Matcher m = NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            String tok = m.group();
            BigDecimal parsed;
            boolean isPercent = tok.endsWith("%");
            try {
                if (isPercent) {
                    parsed = new BigDecimal(tok.substring(0, tok.length() - 1))
                            .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                } else {
                    parsed = new BigDecimal(tok);
                }
            } catch (NumberFormatException e) {
                continue;
            }
            if (!anyMatch(inputNumbers, parsed, isPercent)) {
                return false;
            }
        }
        return true;
    }

    private Set<BigDecimal> collectAllNumbers(JsonNode node) {
        Set<BigDecimal> out = new HashSet<>();
        collectNumbers(node, out);
        return out;
    }

    private void collectNumbers(JsonNode node, Set<BigDecimal> out) {
        if (node == null || node.isNull()) return;
        if (node.isNumber()) {
            out.add(node.decimalValue());
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectNumbers(e.getValue(), out));
        } else if (node.isArray()) {
            for (JsonNode el : node) collectNumbers(el, out);
        } else if (node.isTextual()) {
            // Numeric-looking strings (e.g. "78%") — try to parse
            try {
                out.add(new BigDecimal(node.asText()));
            } catch (NumberFormatException ignored) {
                Matcher pm = PERCENT_PATTERN.matcher(node.asText());
                while (pm.find()) {
                    try {
                        out.add(new BigDecimal(pm.group(1))
                                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                    } catch (NumberFormatException ignored2) {}
                }
            }
        }
    }

    private boolean anyMatch(Set<BigDecimal> candidates, BigDecimal target, boolean isPercent) {
        BigDecimal targetStripped = target.stripTrailingZeros();
        for (BigDecimal c : candidates) {
            BigDecimal cStripped = c.stripTrailingZeros();
            // Direct match with 0.01 absolute tolerance
            if (cStripped.subtract(targetStripped).abs().compareTo(new BigDecimal("0.01")) <= 0) return true;
            // Percent: input may store ratio (0.78) and text says "78%"
            if (isPercent) {
                BigDecimal asRatio = target.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
                if (cStripped.subtract(asRatio).abs().compareTo(new BigDecimal("1")) <= 0) return true;
            }
            // Round to 4 decimals and compare
            BigDecimal c4 = cStripped.setScale(4, RoundingMode.HALF_UP);
            BigDecimal t4 = targetStripped.setScale(4, RoundingMode.HALF_UP);
            if (c4.compareTo(t4) == 0) return true;
        }
        return false;
    }
}
