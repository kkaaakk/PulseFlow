package com.pulseflow.ai.guardrail;

import com.pulseflow.common.exception.PulseFlowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link SensitiveDataSanitizer}.
 *
 * <p>Covers the PII-blocking guardrail: phone numbers, ID cards, emails, and
 * forbidden keys. This is the last line of defence before input is sent to
 * the LLM, so it must block aggressively.</p>
 */
class SensitiveDataSanitizerTest {

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer();

    @Test
    @DisplayName("clean text passes through")
    void cleanTextPasses() {
        String clean = "筛选最近7天活跃5天以上的用户";
        assertThat(sanitizer.inspectText(clean)).isEqualTo(clean);
    }

    @Test
    @DisplayName("null text returns null")
    void nullText() {
        assertThat(sanitizer.inspectText(null)).isNull();
    }

    @Test
    @DisplayName("CN mobile number is blocked")
    void blocksMobile() {
        assertThatThrownBy(() -> sanitizer.inspectText("联系用户13812345678"))
                .isInstanceOf(PulseFlowException.class)
                .hasMessageContaining("Sensitive");
    }

    @Test
    @DisplayName("email is blocked")
    void blocksEmail() {
        assertThatThrownBy(() -> sanitizer.inspectText("send to user@example.com please"))
                .isInstanceOf(PulseFlowException.class)
                .hasMessageContaining("Sensitive");
    }

    @Test
    @DisplayName("ID card number is blocked")
    void blocksIdCard() {
        assertThatThrownBy(() -> sanitizer.inspectText("id=110101199003071234"))
                .isInstanceOf(PulseFlowException.class)
                .hasMessageContaining("Sensitive");
    }

    @Test
    @DisplayName("forbidden key in map is blocked")
    void blocksForbiddenKey() {
        Map<String, Object> input = Map.of("userId", 123L, "metric", "active_7d");
        assertThatThrownBy(() -> sanitizer.inspect(input))
                .isInstanceOf(PulseFlowException.class)
                .hasMessageContaining("Sensitive key 'userId'");
    }

    @Test
    @DisplayName("PII pattern in map value is blocked")
    void blocksPiiInValue() {
        Map<String, Object> input = Map.of("note", "contact 13912345678");
        assertThatThrownBy(() -> sanitizer.inspect(input))
                .isInstanceOf(PulseFlowException.class)
                .hasMessageContaining("Sensitive pattern");
    }

    @Test
    @DisplayName("clean map passes")
    void cleanMapPasses() {
        Map<String, Object> input = Map.of("metric", "active_7d", "value", 5);
        // Should not throw
        sanitizer.inspect(input);
    }

    @Test
    @DisplayName("null map is a no-op")
    void nullMap() {
        sanitizer.inspect(null); // should not throw
    }
}
