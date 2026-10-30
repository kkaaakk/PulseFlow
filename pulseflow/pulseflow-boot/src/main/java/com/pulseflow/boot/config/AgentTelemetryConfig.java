package com.pulseflow.boot.config;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Export metadata only, regardless of environment header/content capture settings. */
@Configuration
public class AgentTelemetryConfig {
    /**
     * 为自动配置的每个 SpanExporter 包装隐私过滤器，在数据离开进程前裁剪内容。
     */
    @Bean
    AutoConfigurationCustomizerProvider privacyCustomizer() {
        return customizer -> customizer.addSpanExporterCustomizer(
                (exporter, ignored) -> new SafeSpanExporter(exporter));
    }

    public static final class SafeSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private static final Set<String> ALLOWED = Set.of(
                "http.request.method", "http.method", "http.response.status_code", "http.status_code",
                "http.route", "server.address", "server.port", "url.scheme", "tool.name",
                "db.system", "db.system.name", "db.name", "db.namespace", "db.operation.name");

        /**
         * 保存底层导出器，由包装层负责过滤 span，底层负责实际发送和资源释放。
         */
        public SafeSpanExporter(SpanExporter delegate) { this.delegate = delegate; }

        /**
         * 生成 span 的安全视图，仅保留白名单属性与服务标识，移除事件、链接及状态描述后交给底层导出器。
         */
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> safe = spans.stream().map(span -> (SpanData) new DelegatingSpanData(span) {
                /**
                 * 仅保留服务名称、版本和遥测 SDK 标识，剔除主机、进程等非白名单资源属性。
                 */
                @Override public Resource getResource() {
                    AttributesBuilder builder = Attributes.builder();
                    span.getResource().getAttributes().forEach((key, value) -> {
                        if (key.getKey().equals("service.name") || key.getKey().equals("service.version")
                                || key.getKey().startsWith("telemetry.sdk.")) put(builder, key, value);
                    });
                    return Resource.create(builder.build());
                }
                /**
                 * 复制明确允许的 span 属性，阻断请求正文、SQL 和凭据等非白名单数据。
                 */
                @Override public Attributes getAttributes() {
                    AttributesBuilder builder = Attributes.builder();
                    span.getAttributes().forEach((key, value) -> {
                        if (ALLOWED.contains(key.getKey())) put(builder, key, value);
                    });
                    return builder.build();
                }
                /**
                 * 返回空事件列表，避免异常事件或事件属性携带业务内容。
                 */
                @Override public List<EventData> getEvents() { return List.of(); }
                /**
                 * 移除 span 链接，避免链接属性绕过当前 span 的白名单过滤。
                 */
                @Override public List<LinkData> getLinks() { return List.of(); }
                /**
                 * 保留状态码并清空状态描述，防止异常消息随状态导出。
                 */
                @Override public StatusData getStatus() {
                    return StatusData.create(span.getStatus().getStatusCode(), "");
                }
            }).toList();
            return delegate.export(safe);
        }

        /**
         * 将通过白名单筛选的属性按原 AttributeKey 类型复制到新属性集合。
         */
        @SuppressWarnings("unchecked")
        private static <T> void put(AttributesBuilder builder, AttributeKey<T> key, Object value) {
            builder.put(key, (T) value);
        }
        /**
         * 委托底层导出器刷新已排队的遥测数据。
         */
        @Override public CompletableResultCode flush() { return delegate.flush(); }
        /**
         * 委托底层导出器停止发送并释放资源。
         */
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}
