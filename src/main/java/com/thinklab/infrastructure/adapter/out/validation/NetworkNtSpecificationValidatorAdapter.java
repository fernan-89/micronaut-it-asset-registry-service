package com.thinklab.infrastructure.adapter.out.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.thinklab.domain.port.SpecificationValidatorPort;
import jakarta.inject.Singleton;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates an Asset's {@code specifications} map against a JSON Schema document fetched from
 * {@code ci-type-catalog-service}, using {@code networknt/json-schema-validator} (this service's own
 * copy of the same library that service uses to syntax-check authored schemas - ADR-027, ADR-031 of
 * {@code ci-type-catalog-service}).
 */
@Singleton
public class NetworkNtSpecificationValidatorAdapter implements SpecificationValidatorPort {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<String> validate(String jsonSchema, Map<String, String> specifications) {
        JsonSchema schema = factory.getSchema(jsonSchema);
        JsonNode payloadNode = objectMapper.valueToTree(specifications);

        Set<ValidationMessage> violations = schema.validate(payloadNode);
        return violations.stream()
                .map(ValidationMessage::getMessage)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
    }
}
