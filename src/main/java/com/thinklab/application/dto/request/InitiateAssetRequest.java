package com.thinklab.application.dto.request;

import com.thinklab.domain.model.Asset.AssetCategory;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * DTO for Asset creation (BIAN Behavior Qualifier: {@code initiate}). Protective barrier to the
 * Domain Layer. organisationId travels via the {@code X-Tenant-Id} header, not the body.
 */
@Serdeable
public record InitiateAssetRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must not exceed 160 characters")
        String name,

        @NotNull(message = "Category is required")
        AssetCategory category,

        @NotBlank(message = "Serial number is required")
        @Size(max = 120, message = "Serial number must not exceed 120 characters")
        String serialNumber,

        Map<String, String> specifications
) {}
