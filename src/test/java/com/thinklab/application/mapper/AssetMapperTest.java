package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateAssetRequest;
import com.thinklab.application.dto.response.AssetAuditEntryResponse;
import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetMapperTest {

    @Test
    @DisplayName("toDomain should build a PROVISIONED aggregate from the request, ID and tenant")
    void toDomain() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        InitiateAssetRequest request = new InitiateAssetRequest("NAS", AssetCategory.STORAGE_ARRAY, "SN-1", Map.of("bays", "8"));

        Asset asset = AssetMapper.toDomain(request, id, organisationId, "exec");

        assertEquals(id, asset.getId());
        assertEquals(organisationId, asset.getOrganisationId());
        assertEquals("NAS", asset.getName());
        assertEquals(AssetCategory.STORAGE_ARRAY, asset.getCategory());
        assertEquals(AssetStatus.PROVISIONED, asset.getStatus());
        assertEquals("exec", asset.getAuditTrail().get(0).executor());
    }

    @Test
    @DisplayName("toResponse should flatten enums to names and copy every field")
    void toResponse() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        Asset asset = Asset.createNew(id, organisationId, "NAS", AssetCategory.STORAGE_ARRAY, "SN-1", Map.of("bays", "8"), "exec");
        asset.assign(holder, location, "exec");

        AssetResponse response = AssetMapper.toResponse(asset);

        assertEquals(id, response.id());
        assertEquals(organisationId, response.organisationId());
        assertEquals("NAS", response.name());
        assertEquals("STORAGE_ARRAY", response.category());
        assertEquals("SN-1", response.serialNumber());
        assertEquals(Map.of("bays", "8"), response.specifications());
        assertEquals("PROVISIONED", response.status());
        assertEquals(holder, response.assignedToUserId());
        assertEquals(location, response.locationId());
        assertEquals(asset.getCreatedAt(), response.createdAt());
        assertEquals(asset.getUpdatedAt(), response.updatedAt());
    }

    @Test
    @DisplayName("toResponse(audit entry) should tolerate a null fromStatus (initiating entry)")
    void auditEntryToResponse() {
        Instant now = Instant.now();

        AssetAuditEntryResponse initiating = AssetMapper.toResponse(
                new AssetAuditEntry(now, "INITIATED", "exec", null, AssetStatus.PROVISIONED, "created"));
        AssetAuditEntryResponse transition = AssetMapper.toResponse(
                new AssetAuditEntry(now, "STATUS_CHANGED", "exec", AssetStatus.READY, AssetStatus.DEPLOYED, "moved"));

        assertNull(initiating.fromStatus());
        assertEquals("PROVISIONED", initiating.toStatus());
        assertEquals("INITIATED", initiating.action());
        assertEquals(now, initiating.occurredAt());
        assertEquals("READY", transition.fromStatus());
        assertEquals("DEPLOYED", transition.toStatus());
        assertEquals("moved", transition.detail());
    }

    @Test
    @DisplayName("The mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<AssetMapper> constructor = AssetMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }
}
