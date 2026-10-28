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

/**
 * 配置 Java 遥测隐私过滤，限制导出字段。
 * Export metadata only, regardless of environment header/content capture settings. */
@Configuration
public class AgentTelemetryConfig {
    /** 安装遥测导出包装器，在 Java span 离开进程前统一过滤内容。 */
    @Bean
    AutoConfigurationCustomizerProvider privacyCustomizer() {
        return customizer -> customizer.addSpanExporterCustomizer(
                (exporter, ignored) -> new SafeSpanExporter(exporter));
    }

    /** 在委托实际导出器前清理 span 业务内容和敏感属性。 */
    public static final class SafeSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private static final Set<String> ALLOWED = Set.of(
                "http.request.method", "http.method", "http.response.status_code", "http.status_code",
                "http.route", "server.address", "server.port", "url.scheme", "tool.name",
                "db.system", "db.system.name", "db.name", "db.namespace", "db.operation.name");

        /** 保存被包装的导出器，集中处理 span 的隐私过滤与生命周期。 */
        public SafeSpanExporter(SpanExporter delegate) { this.delegate = delegate; }

        /** 构造仅含白名单运行字段的 span 快照，再委托实际导出器发送。 */
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> safe = spans.stream().map(span -> (SpanData) new DelegatingSpanData(span) {
                /** 仅保留允许导出的服务和 SDK 资源属性，隐藏其他环境信息。 */
                @Override public Resource getResource() {
                    AttributesBuilder builder = Attributes.builder();
                    span.getResource().getAttributes().forEach((key, value) -> {
                        if (key.getKey().equals("service.name") || key.getKey().equals("service.version")
                                || key.getKey().startsWith("telemetry.sdk.")) put(builder, key, value);
                    });
                    return Resource.create(builder.build());
                }
                /** 返回经过白名单筛选的运行属性，避免 SQL、请求正文及凭据进入遥测。 */
                @Override public Attributes getAttributes() {
                    AttributesBuilder builder = Attributes.builder();
                    span.getAttributes().forEach((key, value) -> {
                        if (ALLOWED.contains(key.getKey())) put(builder, key, value);
                    });
                    return builder.build();
                }
                /** 清空 span 事件，避免异常消息或业务内容被事件采集器带出。 */
                @Override public List<EventData> getEvents() { return List.of(); }
                /** 清空 span 链接，防止链接属性携带未过滤内容。 */
                @Override public List<LinkData> getLinks() { return List.of(); }
                /** 仅保留状态码，清除可能包含原始错误信息的描述。 */
                @Override public StatusData getStatus() {
                    return StatusData.create(span.getStatus().getStatusCode(), "");
                }
            }).toList();
            return delegate.export(safe);
        }

        /** 按属性键的类型写入值，保留 OpenTelemetry 属性的类型契约。 */
        @SuppressWarnings("unchecked")
        private static <T> void put(AttributesBuilder builder, AttributeKey<T> key, Object value) {
            builder.put(key, (T) value);
        }
        /** 委托底层导出器刷新缓冲区，返回其异步完成结果。 */
        @Override public CompletableResultCode flush() { return delegate.flush(); }
        /** 委托底层导出器关闭资源，保持包装器生命周期一致。 */
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}
