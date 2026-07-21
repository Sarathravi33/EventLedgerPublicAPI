package com.eventledger.account;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the encoder configuration in logback-spring.xml (mirrored here) produces exactly
 * the field shape IMPLEMENTATION_PLAN.md §10 documents. Keep this in sync with
 * logback-spring.xml if that file's <fieldNames>/<customFields> ever change.
 */
class StructuredLoggingTest {

    @Test
    void logLineIsSingleLineJsonWithRequiredFields() throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        LogstashFieldNames fieldNames = new LogstashFieldNames();
        fieldNames.setTimestamp("timestamp");
        fieldNames.setLogger("logger");
        encoder.setFieldNames(fieldNames);
        encoder.setCustomFields("{\"service\":\"account-service\"}");
        encoder.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.eventledger.account.StructuredLoggingTest");
        event.setLevel(Level.INFO);
        event.setMessage("structured logging smoke test message");
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(Map.of("traceId", "trace-abc123", "spanId", "span-def456"));

        byte[] encoded = encoder.encode(event);
        String line = new String(encoded, StandardCharsets.UTF_8).strip();

        assertThat(line.lines().count()).as("must be a single log line, got: %s", line).isEqualTo(1);

        JsonNode json = new ObjectMapper().readTree(line);

        assertThat(json.hasNonNull("timestamp")).isTrue();
        assertThat(json.path("level").asText()).isEqualTo("INFO");
        assertThat(json.path("service").asText()).isEqualTo("account-service");
        assertThat(json.path("traceId").asText()).isEqualTo("trace-abc123");
        assertThat(json.path("spanId").asText()).isEqualTo("span-def456");
        assertThat(json.path("logger").asText()).isEqualTo("com.eventledger.account.StructuredLoggingTest");
        assertThat(json.path("message").asText()).isEqualTo("structured logging smoke test message");
    }
}
