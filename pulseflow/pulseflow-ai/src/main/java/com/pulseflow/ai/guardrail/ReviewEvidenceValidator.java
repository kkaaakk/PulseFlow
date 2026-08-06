package com.pulseflow.ai.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.ai.domain.review.ReviewAction;
import com.pulseflow.ai.domain.review.ReviewFinding;
import com.pulseflow.ai.domain.review.ReviewResult;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.common.util.JsonUtil;
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
 * Validates AI review output against the pre-computed input metrics.
 *
 * <p>Same principles as {@link InsightEvidenceValidator}:</p>
 * <ol>
 *   <li>Every evidenceKey must exist in the input JSON.</li>
 *   <li>Numbers in descriptions must appear in the input.</li>
 *   <li>Invalid findings/actions are dropped; fabricated summary numbers fail.</li>
 * </ol>
 */
@Slf4j
@Component
public class ReviewEvidenceValidator {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?%?");
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    public ReviewResult validate(String inputJson, ReviewResult result) {
        JsonNode input = JsonUtil.fromJson(inputJson, JsonNode.class);
        Set<String> validKeys = collectValidKeys(input);

        result.setHighlights(filterFindings(result.getHighlights(), validKeys, input, "highlight"));
        result.setProblems(filterFindings(result.getProblems(), validKeys, input, "problem"));

        List<ReviewAction> keptActions = new ArrayList<>();
        for (ReviewAction a : result.getNextActions() == null ? List.<ReviewAction>of() : result.getNextActions()) {
            if (a.getEvidenceKeys() == null || !validKeys.containsAll(a.getEvidenceKeys())) {
                log.warn("Review action '{}' dropped: invalid evidenceKeys", a.getAction());
                continue;
            }
            if (!numbersExistInInput(a.getReason(), input)) {
                log.warn("Review action '{}' dropped: reason references numbers not in input", a.getAction());
                continue;
            }
            keptActions.add(a);
        }
        result.setNextActions(keptActions);

        if (result.getSummary() != null && !numbersExistInInput(result.getSummary(), input)) {
            throw new AiOutputInvalidException(AiErrorCode.AI_EVIDENCE_INVALID,
                    "Review summary references numbers not present in input: " + result.getSummary());
        }
        return result;
    }

    private List<ReviewFinding> filterFindings(List<ReviewFinding> source,
                                                Set<String> validKeys, JsonNode input, String label) {
        List<ReviewFinding> kept = new ArrayList<>();
        for (ReviewFinding f : source == null ? List.<ReviewFinding>of() : source) {
            if (f.getEvidenceKeys() == null || f.getEvidenceKeys().isEmpty()) {
                log.warn("Review {} '{}' dropped: no evidenceKeys", label, f.getTitle());
                continue;
            }
            if (!validKeys.containsAll(f.getEvidenceKeys())) {
                log.warn("Review {} '{}' dropped: unknown evidenceKeys {}", label, f.getTitle(), f.getEvidenceKeys());
                continue;
            }
            if (!numbersExistInInput(f.getDescription(), input)) {
                log.warn("Review {} '{}' dropped: description references numbers not in input", label, f.getTitle());
                continue;
            }
            kept.add(f);
        }
        return kept;
    }

    private Set<String> collectValidKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        collectKeys(node, "", keys);
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
            out.add(prefix);
        }
    }

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
        // 1. Direct match against any input number
        for (BigDecimal c : candidates) {
            BigDecimal cStripped = c.stripTrailingZeros();
            if (cStripped.subtract(targetStripped).abs().compareTo(new BigDecimal("0.01")) <= 0) return true;
            if (isPercent) {
                BigDecimal asRatio = target.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
                if (cStripped.subtract(asRatio).abs().compareTo(new BigDecimal("1")) <= 0) return true;
            }
            BigDecimal c4 = cStripped.setScale(4, RoundingMode.HALF_UP);
            BigDecimal t4 = targetStripped.setScale(4, RoundingMode.HALF_UP);
            if (c4.compareTo(t4) == 0) return true;
        }
        // 2. Allow derived numbers: differences and relative changes between
        //    any two input numbers. This lets the AI say "提高了 0.3 个百分点"
        //    (3.8% → 4.1%, diff = 0.003) or "相对提升 7.9%"
        //    ((0.041-0.038)/0.038 ≈ 0.079) without being flagged as fabricated.
        java.util.List<BigDecimal> list = new java.util.ArrayList<>(candidates);
        for (int i = 0; i < list.size(); i++) {
            for (int j = 0; j < list.size(); j++) {
                if (i == j) continue;
                BigDecimal a = list.get(i).stripTrailingZeros();
                BigDecimal b = list.get(j).stripTrailingZeros();
                // Difference (百分点): |a - b| ≈ target
                BigDecimal diff = a.subtract(b).abs();
                if (diff.subtract(targetStripped).abs().compareTo(new BigDecimal("0.01")) <= 0) return true;
                if (isPercent) {
                    BigDecimal diffPercent = diff.multiply(BigDecimal.valueOf(100));
                    BigDecimal targetPercent = target.multiply(BigDecimal.valueOf(100));
                    if (diffPercent.subtract(targetPercent).abs().compareTo(new BigDecimal("1")) <= 0) return true;
                } else {
                    // "0.3 个百分点" — the number 0.3 represents 0.3% = 0.003,
                    // so accept target as a percent form of the diff.
                    BigDecimal diffAsPercent = diff.multiply(BigDecimal.valueOf(100));
                    if (diffAsPercent.subtract(targetStripped).abs().compareTo(new BigDecimal("0.05")) <= 0) return true;
                }
                // Relative change: (a - b) / b ≈ target (only when b != 0)
                if (b.signum() != 0) {
                    try {
                        BigDecimal relChange = a.subtract(b)
                                .divide(b.abs(), 6, RoundingMode.HALF_UP);
                        if (relChange.abs().subtract(targetStripped).abs()
                                .compareTo(new BigDecimal("0.005")) <= 0) return true;
                        if (isPercent) {
                            BigDecimal relPercent = relChange.multiply(BigDecimal.valueOf(100));
                            BigDecimal targetPercent = target.multiply(BigDecimal.valueOf(100));
                            if (relPercent.abs().subtract(targetPercent).abs()
                                    .compareTo(new BigDecimal("0.5")) <= 0) return true;
                        }
                    } catch (ArithmeticException ignored) {}
                }
            }
        }
        return false;
    }
}
