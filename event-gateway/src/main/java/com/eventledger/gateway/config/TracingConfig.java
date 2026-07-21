package com.eventledger.gateway.config;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot does not auto-configure a {@link SpanExporter} bean just because
 * opentelemetry-exporter-logging is on the classpath — it must be provided explicitly. The
 * logging exporter (rather than an OTLP endpoint + collector) is a deliberate choice: the brief
 * only requires trace IDs to be generated, propagated, and logged, not visualized, so no
 * collector/backend infrastructure is introduced (IMPLEMENTATION_PLAN.md §17).
 */
@Configuration
public class TracingConfig {

    @Bean
    public SpanExporter otelLoggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}
