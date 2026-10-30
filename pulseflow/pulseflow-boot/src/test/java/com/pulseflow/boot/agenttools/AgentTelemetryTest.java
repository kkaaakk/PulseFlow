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
    /**
     * 返回基础遥测测试使用的预期数据库类型，供子类按实际数据库覆盖。
     */
    protected String expectedDatabaseSystem() { return "h2"; }

    @SpringBootConfiguration
    @ImportAutoConfiguration({AopAutoConfiguration.class, DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            OpenTelemetryAutoConfiguration.class, JdbcInstrumentationAutoConfiguration.class,
            InstrumentationAnnotationsAutoConfiguration.class,
            SpringWebMvc6InstrumentationAutoConfiguration.class})
    @Import({TestController.class, TestService.class, AgentTelemetryConfig.class})
    static class App {
        /**
         * 提供可检查导出 span 的内存测试导出器。
         */
        @Bean AutoConfigurationCustomizerProvider testExporter() {
            return customizer -> customizer.addTracerProviderCustomizer((builder, ignored) ->
                    builder.addSpanProcessor(SimpleSpanProcessor.create(
                            new AgentTelemetryConfig.SafeSpanExporter(EXPORTER))));
        }
    }

    @RestController
    static class TestController {
        final TestService service;
        /**
         * 为测试控制器注入追踪测试服务。
         */
        TestController(TestService service) { this.service = service; }
        /**
         * 把测试 HTTP 请求转发给业务替身服务，验证请求 span 的父子传递。
         */
        @GetMapping("/internal/v1/agent-tools/trace-test")
        int query() { return service.query(); }
    }

    static class TestService {
        final JdbcTemplate jdbc;
        /**
         * 为测试服务注入 JDBC 访问组件，形成可观测调用链。
         */
        TestService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        /**
         * 执行测试数据库查询，生成服务到 JDBC 的下游 span。
         */
        @WithSpan("agent-tools.test-service")
        public int query() { return jdbc.queryForObject("SELECT 1", Integer.class); }
    }

    /**
     * 验证 traceparent 贯穿控制器、服务和 JDBC，导出内容不含秘密请求头。
     */
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

    /**
     * 验证上游误采集正文或异常事件时，安全导出器仍会移除。
     */
    @Test void exporterDropsContentAndExceptionEventsEvenIfCapturedUpstream() {
        EXPORTER.reset();
        var span = telemetry.getTracer("privacy-test").spanBuilder("safe-operation").startSpan();
        span.setAttribute("tool.name", "query_metric");
        span.setAttribute("db.statement", "PRIVATE_SQL_SENTINEL");
        span.setAttribute("db.query.text", "PRIVATE_SQL_TEXT_SENTINEL");
        span.setAttribute("db.query.parameters", "PRIVATE_SQL_PARAMETERS_SENTINEL");
        span.setAttribute("http.request.header.authorization", "PRIVATE_AUTH_SENTINEL");
        span.setAttribute("http.request.header.x_pulseflow_agent_token", "PRIVATE_TOKEN_SENTINEL");
        span.setAttribute("gen_ai.input.messages", "PRIVATE_PROMPT_SENTINEL");
        span.setAttribute("gen_ai.output.messages", "PRIVATE_OUTPUT_SENTINEL");
        span.setAttribute("gen_ai.tool.call.arguments", "PRIVATE_TOOL_ARGUMENTS_SENTINEL");
        span.setAttribute("gen_ai.tool.call.result", "PRIVATE_TOOL_RETURN_SENTINEL");
        span.addEvent("PRIVATE_EVENT_SENTINEL");
        span.recordException(new RuntimeException("PRIVATE_EXCEPTION_SENTINEL"));
        span.setStatus(StatusCode.ERROR, "PRIVATE_STATUS_SENTINEL");
        span.end();
        var exported = EXPORTER.getFinishedSpanItems().stream()
                .filter(item -> item.getName().equals("safe-operation")).findFirst().orElseThrow();
        assertThat(exported.getAttributes().asMap().toString()).contains("query_metric")
                .doesNotContain("PRIVATE_");
        assertThat(exported.getEvents()).isEmpty();
        assertThat(exported.getStatus().getDescription()).isEmpty();
        assertThat(exported.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(exported.getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
    }
}
