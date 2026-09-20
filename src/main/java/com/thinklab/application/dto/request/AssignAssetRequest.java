package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * DTO for binding an Asset to a holder and/or a location (BIAN Behavior Qualifier:
 * {@code assignment/update}). Both fields are optional: {@code null} clears the binding.
 */
@Serdeable
public record AssignAssetRequest(UUID assignedToUserId, UUID locationId) {}
