package com.eventledger.gateway.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataConverterTest {

    private final MetadataConverter converter = new MetadataConverter();

    @Test
    void convertToDatabaseColumn_nullAttribute_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToDatabaseColumn_roundTripsToJson() {
        String json = converter.convertToDatabaseColumn(Map.of("source", "mainframe-batch"));
        assertThat(json).contains("\"source\"", "\"mainframe-batch\"");
    }

    @Test
    void convertToEntityAttribute_nullOrBlank_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("  ")).isNull();
    }

    @Test
    void convertToEntityAttribute_roundTripsFromJson() {
        Map<String, Object> metadata = converter.convertToEntityAttribute("{\"batchId\":\"B-9042\"}");
        assertThat(metadata).containsEntry("batchId", "B-9042");
    }

    @Test
    void convertToEntityAttribute_malformedJson_wrapsExceptionAsIllegalArgument() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{not valid json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to deserialize event metadata from JSON");
    }
}
