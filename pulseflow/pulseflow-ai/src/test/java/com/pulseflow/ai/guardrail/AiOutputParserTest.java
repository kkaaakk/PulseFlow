package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.support.AiOutputInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link AiOutputParser}.
 *
 * <p>Covers the critical JSON-extraction paths: plain JSON, markdown fences,
 * prose-wrapped JSON, empty input, unbalanced input.</p>
 */
class AiOutputParserTest {

    private final AiOutputParser parser = new AiOutputParser();

    @Test
    @DisplayName("plain JSON object is returned as-is")
    void plainJsonObject() {
        Map<String, Object> result = parser.parseMap("{\"a\":1,\"b\":\"x\"}");
        assertThat(result)
                .containsEntry("a", 1)
                .containsEntry("b", "x");
    }

    @Test
    @DisplayName("markdown fenced JSON is extracted")
    void markdownFencedJson() {
        String raw = """
                Here is the result:
                ```json
                {"summary": "ok", "count": 3}
                ```
                """;
        Map<String, Object> result = parser.parseMap(raw);
        assertThat(result)
                .containsEntry("summary", "ok")
                .containsEntry("count", 3);
    }

    @Test
    @DisplayName("fenced JSON without language tag is extracted")
    void markdownFencedJsonNoLang() {
        String raw = "```\n{\"x\": 42}\n```";
        Map<String, Object> result = parser.parseMap(raw);
        assertThat(result).containsEntry("x", 42);
    }

    @Test
    @DisplayName("prose-wrapped JSON is extracted via balanced brace match")
    void proseWrappedJson() {
        String raw = "Sure! Here you go: {\"k\": \"v\", \"nested\": {\"n\": 1}} Thanks!";
        Map<String, Object> result = parser.parseMap(raw);
        assertThat(result).containsEntry("k", "v");
        assertThat(result.get("nested")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("JSON with braces inside string values is handled correctly")
    void bracesInsideStrings() {
        String raw = "{\"msg\": \"contains } char\", \"n\": 1}";
        Map<String, Object> result = parser.parseMap(raw);
        assertThat(result).containsEntry("msg", "contains } char");
        assertThat(result).containsEntry("n", 1);
    }

    @Test
    @DisplayName("empty input throws AiOutputInvalidException")
    void emptyInput() {
        assertThatThrownBy(() -> parser.parseMap(""))
                .isInstanceOf(AiOutputInvalidException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("null input throws")
    void nullInput() {
        assertThatThrownBy(() -> parser.parseMap(null))
                .isInstanceOf(AiOutputInvalidException.class);
    }

    @Test
    @DisplayName("input with no JSON object throws")
    void noJsonObject() {
        assertThatThrownBy(() -> parser.parseMap("just some text, no json here"))
                .isInstanceOf(AiOutputInvalidException.class)
                .hasMessageContaining("no JSON");
    }

    @Test
    @DisplayName("unbalanced JSON throws")
    void unbalancedJson() {
        // The extractor finds a balanced { ... } substring; Jackson then fails
        // to parse the truncated value. The exception is still
        // AiOutputInvalidException.
        assertThatThrownBy(() -> parser.parseMap("{\"a\": 1, \"b\":"))
                .isInstanceOf(AiOutputInvalidException.class);
    }
}
