package com.pulseflow.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class MapperRegistrationTest {
    static class RegistrationProbeComplete extends RuntimeException { }

    @Test void actualApplicationRegistersOnlyDatabaseMappersBeforeOpeningResources() {
        AtomicBoolean checked = new AtomicBoolean();
        new ApplicationContextRunner().withUserConfiguration(PulseFlowApplication.class)
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    assertThat(factory.containsBeanDefinition("userProfileMapper")).isTrue();
                    assertThat(factory.containsBeanDefinition("campaignDraftMapper")).isTrue();
                    assertThat(factory.containsBeanDefinition("campaignPerformanceSummaryMapper")).isTrue();
                    assertThat(factory.containsBeanDefinition("sqlAudiencePreviewService")).isTrue();
                    assertThat(factory.containsBeanDefinition("audiencePreviewService")).isFalse();
                    checked.set(true);
                    // Stop after real scanner registration, before DB/Redis/Kafka initialization.
                    throw new RegistrationProbeComplete();
                })).run(context -> {
                    assertThat(checked.get()).isTrue();
                    assertThat(context.getStartupFailure()).isInstanceOf(RegistrationProbeComplete.class);
                });
    }
}
