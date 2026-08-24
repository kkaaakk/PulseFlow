package com.pulseflow.ai.integration;

import com.pulseflow.ai.application.AudiencePreviewService;
import com.pulseflow.ai.application.CampaignAiDraftService;
import com.pulseflow.ai.application.CampaignIntentService;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.DslValidationResult;
import com.pulseflow.ai.guardrail.AiOutputParser;
import com.pulseflow.ai.guardrail.FakePiiDetectionClient;
import com.pulseflow.ai.guardrail.SensitiveDataSanitizer;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.prompt.CampaignIntentPromptBuilder;
import com.pulseflow.ai.support.AiSensitiveDataDetectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the intent service stops before the prompt/model boundary. */
class CampaignIntentPiiGuardrailTest {

    @Test
    @DisplayName("phone PII is blocked before CampaignIntentPromptBuilder and AiModelClient")
    void blocksPhoneBeforeModelCall() {
        AiModelClient aiModelClient = mock(AiModelClient.class);
        CampaignIntentPromptBuilder promptBuilder = mock(CampaignIntentPromptBuilder.class);

        CampaignIntentService service = new CampaignIntentService(
                aiModelClient,
                promptBuilder,
                mock(AiOutputParser.class),
                mock(com.pulseflow.ai.guardrail.CampaignDslValidator.class),
                new SensitiveDataSanitizer(new FakePiiDetectionClient(
                        FakePiiDetectionClient.Behavior.PII_DETECTED, "PhoneNumber"),
                        new AiMetrics(new SimpleMeterRegistry())),
                mock(AudiencePreviewService.class),
                mock(CampaignAiDraftService.class),
                mock(AiAuditService.class),
                new AiMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> service.parse(7L,
                "给手机号13800138000的用户发送优惠", "Asia/Shanghai"))
                .isInstanceOf(AiSensitiveDataDetectedException.class);

        verify(promptBuilder, never()).build(anyString(), anyString());
        verify(aiModelClient, never()).generateStructured(any());
    }

    @Test
    @DisplayName("clean Chinese intent reaches prompt builder and model")
    void cleanChineseIntentContinuesToModel() {
        AiModelClient aiModelClient = mock(AiModelClient.class);
        CampaignIntentPromptBuilder promptBuilder = mock(CampaignIntentPromptBuilder.class);
        AiOutputParser outputParser = mock(AiOutputParser.class);
        com.pulseflow.ai.guardrail.CampaignDslValidator validator =
                mock(com.pulseflow.ai.guardrail.CampaignDslValidator.class);
        AudiencePreviewService previewService = mock(AudiencePreviewService.class);
        CampaignAiDraftService draftService = mock(CampaignAiDraftService.class);

        when(promptBuilder.build(anyString(), anyString()))
                .thenReturn(new CampaignIntentPromptBuilder.BuiltPrompt("system", "user", "test-v1"));
        when(aiModelClient.generateStructured(any())).thenReturn(AiResponse.builder()
                .provider("fake")
                .model("fake")
                .rawContent("{}")
                .build());
        when(outputParser.parseObject(anyString(), eq(CampaignDsl.class)))
                .thenReturn(new CampaignDsl());
        when(validator.validate(any(CampaignDsl.class))).thenReturn(DslValidationResult.ok(java.util.List.of()));
        when(draftService.saveGeneratedDraft(anyString(), eq(7L), anyString(),
                any(CampaignDsl.class), any(DslValidationResult.class), any()))
                .thenReturn(com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiDraft.builder()
                        .id(1L).validationStatus("VALIDATED").build());

        CampaignIntentService service = new CampaignIntentService(
                aiModelClient,
                promptBuilder,
                outputParser,
                validator,
                new SensitiveDataSanitizer(new FakePiiDetectionClient(),
                        new AiMetrics(new SimpleMeterRegistry())),
                previewService,
                draftService,
                mock(AiAuditService.class),
                new AiMetrics(new SimpleMeterRegistry()));

        service.parse(7L,
                "筛选最近7天活跃不少于5天、最近30天消费超过500元的用户，今晚8点发送满300减30优惠",
                "Asia/Shanghai");

        verify(promptBuilder).build(anyString(), eq("Asia/Shanghai"));
        verify(aiModelClient).generateStructured(any());
    }
}
