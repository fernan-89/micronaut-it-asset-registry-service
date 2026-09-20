package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument.AssetPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument.AuditEntryDocument;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetDocumentTest {

    @Test
    @DisplayName("toDocument / toDomain should round-trip the whole aggregate including the audit ledger")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        Asset asset = Asset.createNew(id, organisationId, "Rack Server", AssetCategory.SERVER, "SN-9", Map.of("cpu", "epyc"), "ops");
        asset.assign(holder, location, "ops");
        asset.markReady("ops");
        asset.deploy("ops");

        AssetDocument doc = AssetPersistenceMapper.toDocument(asset);
        Asset restored = AssetPersistenceMapper.toDomain(doc);

        assertEquals(id, doc.getId());
        assertEquals("DEPLOYED", doc.getStatus());
        assertEquals("SERVER", doc.getCategory());
        assertEquals(4, doc.getAuditTrail().size());
        assertEquals(id, restored.getId());
        assertEquals(organisationId, restored.getOrganisationId());
        assertEquals("Rack Server", restored.getName());
        assertEquals(AssetCategory.SERVER, restored.getCategory());
        assertEquals("SN-9", restored.getSerialNumber());
        assertEquals(Map.of("cpu", "epyc"), restored.getSpecifications());
        assertEquals(AssetStatus.DEPLOYED, restored.getStatus());
        assertEquals(holder, restored.getAssignedToUserId());
        assertEquals(location, restored.getLocationId());
        assertEquals(asset.getCreatedAt(), restored.getCreatedAt());
        assertEquals(asset.getUpdatedAt(), restored.getUpdatedAt());
        assertEquals(asset.getAuditTrail(), restored.getAuditTrail());
    }

    @Test
    @DisplayName("toDomain should default a missing status to PROVISIONED and a missing trail to empty")
    void defaultsWhenFieldsMissing() {
        AssetDocument doc = new AssetDocument();
        doc.setId(UUID.randomUUID());
        doc.setOrganisationId(UUID.randomUUID());
        doc.setName("n");
        doc.setCategory("LAPTOP");
        doc.setSerialNumber("s");
        doc.setAuditTrail(null);

        Asset restored = AssetPersistenceMapper.toDomain(doc);

        assertEquals(AssetStatus.PROVISIONED, restored.getStatus());
        assertTrue(restored.getAuditTrail().isEmpty());
    }

    @Test
    @DisplayName("AuditEntryDocument should convert null statuses both ways")
    void auditEntryNullStatuses() {
        AssetAuditEntry entry = new AssetAuditEntry(Instant.parse("2026-03-01T10:00:00Z"), "INITIATED", "ops", null, AssetStatus.PROVISIONED, "d");

        AuditEntryDocument doc = AuditEntryDocument.fromDomain(entry);

        assertNull(doc.fromStatus());
        assertEquals("PROVISIONED", doc.toStatus());
        assertEquals(entry, doc.toDomain());
    }

    @Test
    @DisplayName("the persistence mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<AssetPersistenceMapper> constructor = AssetPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    @DisplayName("plain accessors should expose what was set (POJO codec contract)")
    void accessors() {
        AssetDocument doc = new AssetDocument();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        doc.setId(id);
        doc.setStatus("READY");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setAssignedToUserId(id);
        doc.setLocationId(id);
        doc.setSpecifications(Map.of("a", "b"));

        assertEquals(id, doc.getId());
        assertEquals("READY", doc.getStatus());
        assertEquals(now, doc.getCreatedAt());
        assertEquals(now, doc.getUpdatedAt());
        assertEquals(id, doc.getAssignedToUserId());
        assertEquals(id, doc.getLocationId());
        assertEquals(Map.of("a", "b"), doc.getSpecifications());
    }
}
