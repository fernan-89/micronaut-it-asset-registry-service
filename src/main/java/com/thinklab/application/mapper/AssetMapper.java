package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateAssetRequest;
import com.thinklab.application.dto.response.AssetAuditEntryResponse;
import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;

import java.util.UUID;

/**
 * Static factory mapper for Asset DTOs and Domain Entities. Enforces the DTO Isolation Pattern.
 */
public final class AssetMapper {

    private AssetMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Asset toDomain(InitiateAssetRequest request, UUID sovereignId, UUID organisationId, String executor) {
        return Asset.createNew(sovereignId, organisationId, request.name(), request.category(),
                request.serialNumber(), request.specifications(), executor);
    }

    public static AssetResponse toResponse(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getOrganisationId(),
                asset.getName(),
                asset.getCategory() != null ? asset.getCategory().name() : null,
                asset.getSerialNumber(),
                asset.getSpecifications(),
                asset.getStatus() != null ? asset.getStatus().name() : null,
                asset.getAssignedToUserId(),
                asset.getLocationId(),
                asset.getCreatedAt(),
                asset.getUpdatedAt()
        );
    }

    public static AssetAuditEntryResponse toResponse(AssetAuditEntry entry) {
        return new AssetAuditEntryResponse(
                entry.occurredAt(),
                entry.action(),
                entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus() != null ? entry.toStatus().name() : null,
                entry.detail()
        );
    }
}
