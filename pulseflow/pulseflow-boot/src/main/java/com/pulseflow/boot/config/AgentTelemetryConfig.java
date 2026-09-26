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

        public SafeSpanExporter(SpanExporter delegate) { this.delegate = delegate; }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> safe = spans.stream().map(span -> (SpanData) new DelegatingSpanData(span) {
                @Override public Resource getResource() {
                    AttributesBuilder builder = Attributes.builder();
                    span.getResource().getAttributes().forEach((key, value) -> {
                        if (key.getKey().equals("service.name") || key.getKey().equals("service.version")
                                || key.getKey().startsWith("telemetry.sdk.")) put(builder, key, value);
                    });
                    return Resource.create(builder.build());
                }
                @Override public Attributes getAttributes() {
                    AttributesBuilder builder = Attributes.builder();
                    span.getAttributes().forEach((key, value) -> {
                        if (ALLOWED.contains(key.getKey())) put(builder, key, value);
                    });
                    return builder.build();
                }
                @Override public List<EventData> getEvents() { return List.of(); }
                @Override public List<LinkData> getLinks() { return List.of(); }
                @Override public StatusData getStatus() {
                    return StatusData.create(span.getStatus().getStatusCode(), "");
                }
            }).toList();
            return delegate.export(safe);
        }

        @SuppressWarnings("unchecked")
        private static <T> void put(AttributesBuilder builder, AttributeKey<T> key, Object value) {
            builder.put(key, (T) value);
        }
        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}
