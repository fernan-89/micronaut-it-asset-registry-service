package com.thinklab.infrastructure.adapter.out.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkNtSpecificationValidatorAdapterTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"cpu\":{\"type\":\"string\"}},\"required\":[\"cpu\"]}";

    private final NetworkNtSpecificationValidatorAdapter adapter = new NetworkNtSpecificationValidatorAdapter();

    @Test
    @DisplayName("should return no violations when specifications conform to the schema")
    void conformingSpecifications() {
        List<String> violations = adapter.validate(SCHEMA, Map.of("cpu", "Intel i7"));

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("should return a violation message when a required property is missing")
    void missingRequiredProperty() {
        List<String> violations = adapter.validate(SCHEMA, Map.of());

        assertFalse(violations.isEmpty());
        assertTrue(violations.get(0).contains("cpu"));
    }

    @Test
    @DisplayName("should treat a null specifications map as an empty payload and report the mismatch")
    void nullSpecifications() {
        List<String> violations = adapter.validate(SCHEMA, null);

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("should return violations sorted in natural order")
    void violationsAreSorted() {
        String multiRequiredSchema = "{\"type\":\"object\",\"required\":[\"ram\",\"cpu\"]}";

        List<String> violations = adapter.validate(multiRequiredSchema, Map.of());

        assertEquals(2, violations.size());
        List<String> sorted = violations.stream().sorted().toList();
        assertEquals(sorted, violations);
    }
}
