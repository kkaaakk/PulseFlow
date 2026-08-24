package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiPiiGuardrailUnavailableException;
import com.pulseflow.ai.support.AiSensitiveDataDetectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the local business guardrail + provider-neutral PII layer. */
class SensitiveDataSanitizerTest {

    private SensitiveDataSanitizer sanitizer(PiiDetectionClient client) {
        return new SensitiveDataSanitizer(client, new AiMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("normal Chinese campaign text passes the clean PII result")
    void cleanTextPasses() {
        String clean = "筛选最近7天活跃不少于5天、最近30天消费超过500元的用户，今晚8点发送满300减30优惠";
        assertThat(sanitizer(new FakePiiDetectionClient()).inspectText(clean)).isEqualTo(clean);
    }

    @Test
    @DisplayName("null and blank text do not call the provider path")
    void nullAndBlankText() {
        SensitiveDataSanitizer sanitizer = sanitizer(new FakePiiDetectionClient(
                FakePiiDetectionClient.Behavior.PROVIDER_FAILURE));
        assertThat(sanitizer.inspectText(null)).isNull();
        assertThat(sanitizer.inspectText("  ")).isEqualTo("  ");
    }

    @Test
    @DisplayName("Azure/Fake PhoneNumber category blocks without exposing the original value")
    void blocksPhoneNumberFromPiiClient() {
        String phone = "给手机号13800138000的用户发送优惠";
        assertThatThrownBy(() -> sanitizer(new FakePiiDetectionClient(
                FakePiiDetectionClient.Behavior.PII_DETECTED, "PhoneNumber")).inspectText(phone))
                .isInstanceOf(AiSensitiveDataDetectedException.class)
                .satisfies(error -> {
                    AiSensitiveDataDetectedException e = (AiSensitiveDataDetectedException) error;
                    assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.AI_SENSITIVE_DATA_DETECTED);
                    assertThat(e.getMessage()).contains("PhoneNumber");
                    assertThat(e.getMessage()).doesNotContain("13800138000");
                });
    }

    @Test
    @DisplayName("Person and Address categories are blocked")
    void blocksPersonAndAddress() {
        for (String category : new String[]{"Person", "Address"}) {
            assertThatThrownBy(() -> sanitizer(new FakePiiDetectionClient(
                    FakePiiDetectionClient.Behavior.PII_DETECTED, category)
            ).inspectText("中文自然语言输入"))
                    .isInstanceOf(AiSensitiveDataDetectedException.class)
                    .hasMessageContaining(category);
        }
    }

    @Test
    @DisplayName("business forbidden fields are blocked even when PII provider returns CLEAN")
    void blocksForbiddenBusinessField() {
        Map<String, Object> input = Map.of("userId", 123L, "metric", "active_7d");

        assertThatThrownBy(() -> sanitizer(new FakePiiDetectionClient()).inspect(input))
                .isInstanceOf(AiSensitiveDataDetectedException.class)
                .hasMessageContaining("BUSINESS_FIELD_userId")
                .hasMessageNotContaining("123");

        assertThatThrownBy(() -> sanitizer(new FakePiiDetectionClient()).inspect(Map.of(
                "rawEvents", java.util.List.of(Map.of("event", "CLICK")))))
                .isInstanceOf(AiSensitiveDataDetectedException.class)
                .hasMessageContaining("BUSINESS_FIELD_rawEvents");
    }

    @Test
    @DisplayName("PII timeout fails closed")
    void timeoutFailsClosed() {
        assertThatThrownBy(() -> sanitizer(new FakePiiDetectionClient(
                FakePiiDetectionClient.Behavior.TIMEOUT)).inspectText("中文输入"))
                .isInstanceOf(AiPiiGuardrailUnavailableException.class)
                .hasMessageContaining("PII guardrail temporarily unavailable");
    }

    @Test
    @DisplayName("provider failure fails closed")
    void providerFailureFailsClosed() {
        assertThatThrownBy(() -> sanitizer(new FakePiiDetectionClient(
                FakePiiDetectionClient.Behavior.PROVIDER_FAILURE)).inspectText("中文输入"))
                .isInstanceOf(AiPiiGuardrailUnavailableException.class)
                .hasMessageContaining("PII guardrail temporarily unavailable");
    }

    @Test
    @DisplayName("clean structured input passes")
    void cleanMapPasses() {
        sanitizer(new FakePiiDetectionClient()).inspect(Map.of(
                "metric", "active_7d",
                "value", 5,
                "nested", Map.of("label", "aggregated")));
    }

    @Test
    @DisplayName("null map is a no-op")
    void nullMap() {
        sanitizer(new FakePiiDetectionClient(
                FakePiiDetectionClient.Behavior.PROVIDER_FAILURE)).inspect(null);
    }
}
