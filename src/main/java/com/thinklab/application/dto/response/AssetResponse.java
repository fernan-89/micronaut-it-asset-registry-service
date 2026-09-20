package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for Asset output payload (IT Asset Registry Control Record). Enforces the DTO Isolation
 * Pattern by preventing the pure Domain Model from bleeding out to the HTTP boundary.
 */
@Serdeable
public record AssetResponse(
        UUID id,
        UUID organisationId,
        String name,
        String category,
        String serialNumber,
        Map<String, String> specifications,
        String status,
        UUID assignedToUserId,
        UUID locationId,
        Instant createdAt,
        Instant updatedAt
) {}
