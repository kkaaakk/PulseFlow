package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.config.AgentTelemetryConfig;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.jdbc.JdbcInstrumentationAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.annotations.InstrumentationAnnotationsAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.webmvc.SpringWebMvc6InstrumentationAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AgentTelemetry 的业务行为和边界条件。 */
@SpringBootTest(classes = AgentTelemetryTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"otel.sdk.disabled=false", "otel.traces.exporter=none",
                "otel.metrics.exporter=none", "otel.logs.exporter=none",
                "spring.datasource.url=jdbc:h2:mem:oteltest", "spring.datasource.username=sa",
                "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.flyway.enabled=false"})
class AgentTelemetryTest {
    static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();
    @Autowired TestRestTemplate http;
    @Autowired OpenTelemetry telemetry;
    /** 返回当前测试所期望的数据库类型，供遥测断言复用。 */
    protected String expectedDatabaseSystem() { return "h2"; }

    /** 为当前测试提供独立的配置或依赖替身，隔离真实应用资源。 */
    @SpringBootConfiguration
    @ImportAutoConfiguration({AopAutoConfiguration.class, DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            OpenTelemetryAutoConfiguration.class, JdbcInstrumentationAutoConfiguration.class,
            InstrumentationAnnotationsAutoConfiguration.class,
            SpringWebMvc6InstrumentationAutoConfiguration.class})
    @Import({TestController.class, TestService.class, AgentTelemetryConfig.class})
    static class App {
        /** 注册内存 span 导出器，供测试读取并断言遥测内容。 */
        @Bean AutoConfigurationCustomizerProvider testExporter() {
            return customizer -> customizer.addTracerProviderCustomizer((builder, ignored) ->
                    builder.addSpanProcessor(SimpleSpanProcessor.create(
                            new AgentTelemetryConfig.SafeSpanExporter(EXPORTER))));
        }
    }

    /** 为当前测试提供独立的配置或依赖替身，隔离真实应用资源。 */
    @RestController
    static class TestController {
        final TestService service;
        /** 注入测试依赖。 */
        TestController(TestService service) { this.service = service; }
        /** 执行用于验证 HTTP、服务与数据库 trace 传播的测试查询。 */
        @GetMapping("/internal/v1/agent-tools/trace-test")
        int query() { return service.query(); }
    }

    /** 为当前测试提供独立的配置或依赖替身，隔离真实应用资源。 */
    static class TestService {
        final JdbcTemplate jdbc;
        /** 注入测试依赖。 */
        TestService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        /** 执行用于验证 HTTP、服务与数据库 trace 传播的测试查询。 */
        @WithSpan("agent-tools.test-service")
        public int query() { return jdbc.queryForObject("SELECT 1", Integer.class); }
    }

    /** 验证 traceparent 跨 HTTP、服务及 JDBC 传播，同时不会采集敏感请求头。 */
    @Test void traceparentReachesServiceAndJdbcWithoutSecretHeaders() {
        EXPORTER.reset();
        String traceId = "1234567890abcdef1234567890abcdef";
        HttpHeaders headers = new HttpHeaders();
        headers.set("traceparent", "00-" + traceId + "-1234567890abcdef-01");
        headers.set("X-PulseFlow-Agent-Token", "PRIVATE_TOKEN_SENTINEL");
        assertThat(http.exchange("/internal/v1/agent-tools/trace-test", HttpMethod.GET,
                new HttpEntity<>(headers), Integer.class).getBody()).isEqualTo(1);
        var spans = EXPORTER.getFinishedSpanItems().stream()
                .filter(span -> traceId.equals(span.getTraceId())).toList();
        assertThat(spans).anySatisfy(span -> assertThat(span.getName())
                .isEqualTo("agent-tools.test-service"));
        assertThat(spans).anySatisfy(span -> assertThat(span.getAttributes().asMap().toString())
                .contains(expectedDatabaseSystem()));
        assertThat(spans.toString()).doesNotContain("PRIVATE_TOKEN_SENTINEL", "SELECT 1");
    }

    /** 验证安全导出器会清理上游误采集的内容和异常事件。 */
    @Test void exporterDropsContentAndExceptionEventsEvenIfCapturedUpstream() {
        EXPORTER.reset();
        var span = telemetry.getTracer("privacy-test").spanBuilder("safe-operation").startSpan();
        span.setAttribute("tool.name", "query_metric");
        span.setAttribute("db.statement", "PRIVATE_SQL_SENTINEL");
        span.setAttribute("http.request.header.x_pulseflow_agent_token", "PRIVATE_TOKEN_SENTINEL");
        span.recordException(new RuntimeException("PRIVATE_EXCEPTION_SENTINEL"));
        span.setStatus(StatusCode.ERROR, "PRIVATE_STATUS_SENTINEL");
        span.end();
        var exported = EXPORTER.getFinishedSpanItems().stream()
                .filter(item -> item.getName().equals("safe-operation")).findFirst().orElseThrow();
        assertThat(exported.getAttributes().asMap().toString()).contains("query_metric")
                .doesNotContain("PRIVATE_");
        assertThat(exported.getEvents()).isEmpty();
        assertThat(exported.getStatus().getDescription()).isEmpty();
    }
}
