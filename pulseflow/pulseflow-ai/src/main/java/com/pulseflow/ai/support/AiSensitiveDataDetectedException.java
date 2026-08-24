package com.pulseflow.ai.support;

import com.pulseflow.common.exception.PulseFlowException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Thrown when a local business rule or PII provider blocks AI input. */
public class AiSensitiveDataDetectedException extends PulseFlowException {

    private final Set<String> categories;

    public AiSensitiveDataDetectedException(Collection<String> categories) {
        super(AiErrorCode.AI_SENSITIVE_DATA_DETECTED, safeMessage(categories));
        this.categories = safeCategories(categories);
    }

    public Set<String> getCategories() {
        return categories;
    }

    private static String safeMessage(Collection<String> categories) {
        return "AI input contains sensitive personal information (categories: "
                + String.join(", ", safeCategories(categories)) + ")";
    }

    private static Set<String> safeCategories(Collection<String> categories) {
        Set<String> result = new LinkedHashSet<>();
        if (categories != null) {
            for (String category : categories) {
                if (category == null || category.isBlank()) continue;
                String safe = category.replaceAll("[^A-Za-z0-9_.-]", "_");
                result.add(safe.length() > 64 ? safe.substring(0, 64) : safe);
            }
        }
        if (result.isEmpty()) result.add("Unknown");
        return Set.copyOf(result);
    }
}
