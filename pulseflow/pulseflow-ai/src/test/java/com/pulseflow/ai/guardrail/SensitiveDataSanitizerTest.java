package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiPiiGuardrailUnavailableException;
import com.pulseflow.ai.support.AiSensitiveDataDetectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @DisplayName("clean Chinese intent invokes the PII provider after business-field check")
    void cleanChineseTextInvokesPiiProvider() {
        String clean = "筛选最近7天活跃不少于5天、最近30天消费超过500元的用户";
        PiiDetectionClient client = mock(PiiDetectionClient.class);
        when(client.detect(clean)).thenReturn(PiiDetectionResult.clean("fake-pii"));

        assertThat(sanitizer(client).inspectText(clean)).isEqualTo(clean);
        verify(client).detect(clean);
    }

    @Test
    @DisplayName("natural-language business identifiers block before the PII provider")
    void blocksNaturalLanguageBusinessIdentifiersBeforePiiProvider() {
        List<String> inputs = List.of(
                "给 userId 123456 的用户发送优惠券",
                "把 rawEvents 里的用户筛出来",
                "根据 orderDetails 给这些用户推送优惠",
                "筛选 deviceId 对应的用户",
                "分析 behaviourLogs 后给用户发消息");

        for (String input : inputs) {
            PiiDetectionClient client = mock(PiiDetectionClient.class);
            assertThatThrownBy(() -> sanitizer(client).inspectText(input))
                    .isInstanceOf(AiSensitiveDataDetectedException.class)
                    .hasMessageNotContaining("123456");
            verify(client, never()).detect(anyString());
        }
    }

    @Test
    @DisplayName("business identifier matching is case-insensitive and boundary-aware")
    void businessIdentifierMatchingIsCaseInsensitiveAndBoundaryAware() {
        for (String input : List.of("userid 123", "USERID 123", "customerUserIdAlias")) {
            PiiDetectionClient client = mock(PiiDetectionClient.class);
            if (input.startsWith("customer")) {
                when(client.detect(input)).thenReturn(PiiDetectionResult.clean("fake-pii"));
                sanitizer(client).inspectText(input);
                verify(client).detect(input);
            } else {
                assertThatThrownBy(() -> sanitizer(client).inspectText(input))
                        .isInstanceOf(AiSensitiveDataDetectedException.class)
                        .hasMessageContaining("BUSINESS_FIELD_userId");
                verify(client, never()).detect(anyString());
            }
        }
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
