package com.pulseflow.ai.guardrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiOutputInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts JSON from LLM raw output and parses it into domain types.
 *
 * <p>Handles:</p>
 * <ul>
 *   <li>Plain JSON.</li>
 *   <li>Markdown code-fenced JSON (```json ... ``` or ``` ... ```).</li>
 *   <li>JSON surrounded by prose (greedy first { ... } match).</li>
 *   <li>Empty / null input → {@link AiOutputInvalidException} with {@code AI_EMPTY_RESPONSE}.</li>
 *   <li>Unparseable input → {@link AiOutputInvalidException} with {@code AI_INVALID_JSON}.</li>
 * </ul>
 */
@Slf4j
@Component
public class AiOutputParser {

    private static final Pattern FENCED_JSON = Pattern.compile(
            "```(?:json|JSON)?\\s*([\\s\\S]*?)\\s*```");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Extract the largest balanced JSON object from {@code raw} and parse to
     * the requested type.
     */
    public <T> T parseObject(String raw, Class<T> type) {
        String json = extractJson(raw);
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new AiOutputInvalidException(AiErrorCode.AI_INVALID_JSON,
                    "Failed to parse AI output as " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parse to a generic {@link Map} for schema-light callers.
     */
    public Map<String, Object> parseMap(String raw) {
        String json = extractJson(raw);
        try {
            ObjectReader reader = MAPPER.readerFor(new TypeReference<Map<String, Object>>() {});
            Map<String, Object> result = reader.readValue(json);
            return result != null ? result : new LinkedHashMap<>();
        } catch (Exception e) {
            throw new AiOutputInvalidException(AiErrorCode.AI_INVALID_JSON,
                    "Failed to parse AI output as Map: " + e.getMessage(), e);
        }
    }

    /**
     * Parse to a {@link JsonNode} for validators that need structural inspection.
     */
    public JsonNode parseTree(String raw) {
        String json = extractJson(raw);
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AiOutputInvalidException(AiErrorCode.AI_INVALID_JSON,
                    "Failed to parse AI output as tree: " + e.getMessage(), e);
        }
    }

    /**
     * Extract the JSON substring from a possibly-markdown-wrapped response.
     */
    public String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiOutputInvalidException(AiErrorCode.AI_EMPTY_RESPONSE,
                    "AI returned empty content");
        }

        String trimmed = raw.trim();

        // 1. Already valid JSON object/array
        if ((trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return trimmed;
        }

        // 2. Markdown fenced code block
        Matcher m = FENCED_JSON.matcher(trimmed);
        if (m.find()) {
            String inner = m.group(1).trim();
            if (!inner.isEmpty()) return inner;
        }

        // 3. First balanced { ... } block
        int start = trimmed.indexOf('{');
        if (start < 0) {
            throw new AiOutputInvalidException(AiErrorCode.AI_INVALID_JSON,
                    "AI output contains no JSON object: " + truncate(trimmed, 200));
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return trimmed.substring(start, i + 1);
            }
        }
        throw new AiOutputInvalidException(AiErrorCode.AI_INVALID_JSON,
                "AI output JSON is unbalanced: " + truncate(trimmed, 200));
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
