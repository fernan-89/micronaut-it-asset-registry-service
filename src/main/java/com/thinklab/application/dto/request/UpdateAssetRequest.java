package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * DTO for updating the descriptive information of an Asset (BIAN Behavior Qualifier: {@code update}).
 */
@Serdeable
public record UpdateAssetRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must not exceed 160 characters")
        String name,

        Map<String, String> specifications
) {}
