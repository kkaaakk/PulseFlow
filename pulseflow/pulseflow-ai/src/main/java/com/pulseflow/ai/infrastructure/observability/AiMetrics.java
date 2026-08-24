package com.pulseflow.ai.infrastructure.observability;

import com.pulseflow.ai.support.AiTaskType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer-backed AI observability.
 *
 * <p>Meter names follow the design doc §15 list. All meters are tagged with
 * {@code taskType} and {@code provider}.</p>
 *
 * <p>In production {@code spring-boot-starter-actuator} (a dependency of
 * pulseflow-boot) always supplies a {@link MeterRegistry}. All recording
 * methods guard against a null registry so observability stays best-effort
 * and never blocks the AI pipeline.</p>
 */
@Slf4j
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    @Autowired
    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(AiTaskType taskType, String provider, Duration latency, boolean success) {
        if (registry == null) return;
        try {
            Timer.builder("pulseflow_ai_request_latency")
                    .tag("taskType", taskType.name())
                    .tag("provider", provider)
                    .tag("result", success ? "success" : "failure")
                    .register(registry)
                    .record(latency);

            Counter.builder("pulseflow_ai_requests_total")
                    .tag("taskType", taskType.name())
                    .tag("provider", provider)
                    .tag("result", success ? "success" : "failure")
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.debug("AI metrics recording failed: {}", e.getMessage());
        }
    }

    public void recordTokens(AiTaskType taskType, String provider, int promptTokens, int completionTokens) {
        if (registry == null) return;
        try {
            Counter.builder("pulseflow_ai_tokens_total")
                    .tag("taskType", taskType.name())
                    .tag("provider", provider)
                    .tag("kind", "prompt")
                    .register(registry)
                    .increment(Math.max(0, promptTokens));

            Counter.builder("pulseflow_ai_tokens_total")
                    .tag("taskType", taskType.name())
                    .tag("provider", provider)
                    .tag("kind", "completion")
                    .register(registry)
                    .increment(Math.max(0, completionTokens));
        } catch (Exception e) {
            log.debug("AI token metrics recording failed: {}", e.getMessage());
        }
    }

    public void recordFailure(AiTaskType taskType, String failureType) {
        if (registry == null) return;
        try {
            Counter.builder("pulseflow_ai_failures_total")
                    .tag("taskType", taskType.name())
                    .tag("failureType", failureType)
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.debug("AI failure metrics recording failed: {}", e.getMessage());
        }
    }

    /**
     * Records one PII guardrail attempt. The value of {@code result} is one of
     * {@code clean}, {@code blocked}, or {@code failure}; no input text is
     * attached to any meter.
     */
    public void recordPiiDetection(String provider, Duration latency, String result) {
        if (registry == null) return;
        try {
            String safeProvider = provider == null || provider.isBlank() ? "unknown" : provider;
            String safeResult = result == null || result.isBlank() ? "unknown" : result;
            Timer.builder("pulseflow_pii_detection_latency")
                    .tag("provider", safeProvider)
                    .tag("result", safeResult)
                    .register(registry)
                    .record(latency == null ? Duration.ZERO : latency);

            Counter.builder("pulseflow_pii_detection_requests")
                    .tag("provider", safeProvider)
                    .tag("result", safeResult)
                    .register(registry)
                    .increment();

            if ("blocked".equals(safeResult)) {
                Counter.builder("pulseflow_pii_detection_blocked")
                        .tag("provider", safeProvider)
                        .register(registry)
                        .increment();
            } else if ("failure".equals(safeResult)) {
                Counter.builder("pulseflow_pii_detection_failures")
                        .tag("provider", safeProvider)
                        .register(registry)
                        .increment();
            }
        } catch (Exception e) {
            log.debug("PII metrics recording failed: {}", e.getMessage());
        }
    }
}
