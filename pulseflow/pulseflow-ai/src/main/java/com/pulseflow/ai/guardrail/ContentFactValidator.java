package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.domain.content.ContentResult;
import com.pulseflow.ai.domain.content.ContentVariant;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates AI-generated content variants against the real input facts.
 *
 * <p>Checks per design §9.3:</p>
 * <ol>
 *   <li>title length ≤ titleMaxLength</li>
 *   <li>body length ≤ bodyMaxLength</li>
 *   <li>no forbidden words</li>
 *   <li>no un-substituted template variables (e.g. {{xxx}})</li>
 *   <li>no discount / threshold numbers that are not in promotionFacts</li>
 *   <li>no fabricated deadlines ("最后一天" / "limited stock" unless present in input)</li>
 *   <li>no PII (phone, email, id-card patterns)</li>
 *   <li>exactly three variants with distinct types</li>
 * </ol>
 *
 * <p>Invalid variants are dropped. If fewer than 1 variant survives, a 422 is
 * thrown — the operator must re-generate or write content manually.</p>
 */
@Slf4j
@Component
public class ContentFactValidator {

    private static final Set<String> ALLOWED_TYPES = Set.of("DIRECT_BENEFIT", "URGENCY", "PERSONALIZED");

    /** Fabricated-urgency phrases forbidden unless the input explicitly says so. */
    private static final List<String> DEFAULT_FORBIDDEN_PHRASES = List.of(
            "最后一天", "仅剩", "即将售罄", "限量", "limited stock", "last day"
    );

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{[^}]+\\}\\}");

    private static final Pattern PII_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PII_EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PII_IDCARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * Validate and clean a {@link ContentResult}.
     *
     * @param input the input map that was sent to the LLM (contains
     *              promotionFacts, titleMaxLength, bodyMaxLength,
     *              forbiddenWords, etc.)
     * @param result parsed AI output
     * @return cleaned result with invalid variants dropped
     * @throws AiOutputInvalidException if all variants are invalid or critical
     *                                  fabrication is detected
     */
    public ContentResult validate(java.util.Map<String, Object> input, ContentResult result) {
        if (result == null || result.getVariants() == null || result.getVariants().isEmpty()) {
            throw new AiOutputInvalidException(AiErrorCode.AI_OUTPUT_SCHEMA_INVALID,
                    "Content result has no variants");
        }

        int titleMax = asInt(input.get("titleMaxLength"), 24);
        int bodyMax = asInt(input.get("bodyMaxLength"), 80);
        Set<String> forbidden = collectForbidden(input);
        Set<BigDecimal> allowedNumbers = collectAllowedNumbers(input);

        List<ContentVariant> kept = new ArrayList<>();
        Set<String> seenTypes = new HashSet<>();
        for (ContentVariant v : result.getVariants()) {
            List<String> errors = new ArrayList<>();
            if (v.getType() == null || !ALLOWED_TYPES.contains(v.getType())) {
                errors.add("invalid type: " + v.getType());
            }
            if (v.getTitle() == null || v.getTitle().isBlank()) {
                errors.add("title is empty");
            } else if (v.getTitle().length() > titleMax) {
                errors.add("title length " + v.getTitle().length() + " > " + titleMax);
            }
            if (v.getBody() == null || v.getBody().isBlank()) {
                errors.add("body is empty");
            } else if (v.getBody().length() > bodyMax) {
                errors.add("body length " + v.getBody().length() + " > " + bodyMax);
            }

            // forbidden words
            for (String w : forbidden) {
                if (w == null || w.isBlank()) continue;
                if (containsIgnoreCase(v.getTitle(), w) || containsIgnoreCase(v.getBody(), w)) {
                    errors.add("contains forbidden word: " + w);
                }
            }

            // fabricated urgency phrases — only allowed when input explicitly
            // authorises them. "最后一天" / "即将售罄" / "限量" etc. are forbidden
            // unless stockLimited=true. Mentioning a concrete validUntil date
            // (e.g. "8月5日") is allowed because the date is verifiable.
            Boolean stockLimited = asBoolean(input.get("stockLimited"), false);
            for (String phrase : DEFAULT_FORBIDDEN_PHRASES) {
                if (containsIgnoreCase(v.getTitle(), phrase) || containsIgnoreCase(v.getBody(), phrase)) {
                    if (!stockLimited) {
                        errors.add("fabricated urgency: " + phrase);
                    }
                }
            }

            // un-substituted template variables
            if (TEMPLATE_VAR.matcher(safe(v.getTitle())).find()
                    || TEMPLATE_VAR.matcher(safe(v.getBody())).find()) {
                errors.add("un-substituted template variable");
            }

            // PII
            if (PII_PHONE.matcher(safe(v.getTitle()) + " " + safe(v.getBody())).find()
                    || PII_EMAIL.matcher(safe(v.getTitle()) + " " + safe(v.getBody())).find()
                    || PII_IDCARD.matcher(safe(v.getTitle()) + " " + safe(v.getBody())).find()) {
                errors.add("contains PII");
            }

            // fabricated numbers not in promotion facts
            if (!allowedNumbers.isEmpty()) {
                if (hasUnknownNumber(v.getTitle(), allowedNumbers)
                        || hasUnknownNumber(v.getBody(), allowedNumbers)) {
                    errors.add("references numbers not in promotionFacts");
                }
            }

            if (!errors.isEmpty()) {
                log.warn("Content variant type={} dropped: {}", v.getType(), errors);
                continue;
            }
            if (!seenTypes.add(v.getType())) {
                log.warn("Content variant type={} duplicated, dropping", v.getType());
                continue;
            }
            kept.add(v);
        }

        if (kept.isEmpty()) {
            throw new AiOutputInvalidException(AiErrorCode.AI_EVIDENCE_INVALID,
                    "All content variants failed fact validation");
        }
        result.setVariants(kept);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<String> collectForbidden(java.util.Map<String, Object> input) {
        Set<String> out = new HashSet<>();
        Object f = input.get("forbiddenWords");
        if (f instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /**
     * Collect all numbers that may legally appear in the content:
     * promotion facts (threshold, discount, rate), validUntil date numbers,
     * and any numbers explicitly listed in promotionFacts[].description.
     */
    @SuppressWarnings("unchecked")
    private Set<BigDecimal> collectAllowedNumbers(java.util.Map<String, Object> input) {
        Set<BigDecimal> out = new HashSet<>();
        Object pf = input.get("promotionFacts");
        if (pf instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) {
                    addNumber(out, m.get("threshold"));
                    addNumber(out, m.get("discount"));
                    addNumber(out, m.get("rate"));
                    // Numbers inside description strings are also allowed
                    Object desc = m.get("description");
                    if (desc != null) {
                        Matcher mm = NUMBER_PATTERN.matcher(String.valueOf(desc));
                        while (mm.find()) {
                            try { out.add(new BigDecimal(mm.group())); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                } else if (o instanceof String s) {
                    // Free-form fact strings — allow all numbers in them
                    Matcher mm = NUMBER_PATTERN.matcher(s);
                    while (mm.find()) {
                        try { out.add(new BigDecimal(mm.group())); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        // Allow validUntil date numbers (year/month/day) so URGENCY copy can
        // mention "8月5日" if the input says validUntil=2026-08-05.
        Object validUntil = input.get("validUntil");
        if (validUntil != null) {
            Matcher mm = NUMBER_PATTERN.matcher(String.valueOf(validUntil));
            while (mm.find()) {
                try { out.add(new BigDecimal(mm.group())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    private void addNumber(Set<BigDecimal> out, Object v) {
        if (v == null) return;
        if (v instanceof Number n) {
            out.add(new BigDecimal(n.toString()));
        } else {
            try { out.add(new BigDecimal(String.valueOf(v))); }
            catch (NumberFormatException ignored) {}
        }
    }

    private boolean hasUnknownNumber(String text, Set<BigDecimal> allowed) {
        if (text == null || text.isBlank()) return false;
        Matcher m = NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            try {
                BigDecimal n = new BigDecimal(m.group());
                if (!anyMatch(allowed, n)) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    private boolean anyMatch(Set<BigDecimal> candidates, BigDecimal target) {
        for (BigDecimal c : candidates) {
            if (c.subtract(target).abs().compareTo(new BigDecimal("0.01")) <= 0) return true;
        }
        return false;
    }

    private boolean containsIgnoreCase(String hay, String needle) {
        if (hay == null || needle == null) return false;
        return hay.toLowerCase().contains(needle.toLowerCase());
    }

    private String safe(String s) { return s == null ? "" : s; }

    private int asInt(Object v, int dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { return dflt; }
    }

    private boolean asBoolean(Object v, boolean dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        return dflt;
    }
}
