package com.thinklab.domain.port;

import java.util.List;
import java.util.Map;

/**
 * Outbound Port: validates an Asset's {@code specifications} payload against a JSON Schema document
 * fetched from {@code ci-type-catalog-service} (ADR-027). This service only ever validates - it never
 * authors or stores schemas itself, that is {@code ci-type-catalog-service}'s own job.
 */
public interface SpecificationValidatorPort {

    /**
     * @return the list of human-readable violation messages; empty when {@code specifications} conforms
     *         to {@code jsonSchema}.
     */
    List<String> validate(String jsonSchema, Map<String, String> specifications);
}
