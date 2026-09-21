package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the Asset Aggregate for MongoDB. Ensures the pure Domain
 * Model remains untainted by persistence annotations. Uses native BSON annotations for
 * high-performance mapping without ORM overhead.
 */
@Introspected
public class AssetDocument {

    @BsonId // Native MongoDB driver annotation for Sovereign Identity
    private UUID id;

    private UUID organisationId;
    private String name;
    private String category;
    private String serialNumber;
    private Map<String, String> specifications = new LinkedHashMap<>();
    private String status;
    private UUID assignedToUserId;
    private UUID locationId;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    // Getters and Setters required by framework POJO codec
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public Map<String, String> getSpecifications() { return specifications; }
    public void setSpecifications(Map<String, String> specifications) { this.specifications = specifications; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getAssignedToUserId() { return assignedToUserId; }
    public void setAssignedToUserId(UUID assignedToUserId) { this.assignedToUserId = assignedToUserId; }
    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor,
                                     String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(AssetAuditEntry entry) {
            return new AuditEntryDocument(
                    entry.occurredAt(),
                    entry.action(),
                    entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(),
                    entry.detail()
            );
        }

        public AssetAuditEntry toDomain() {
            return new AssetAuditEntry(
                    occurredAt,
                    action,
                    executor,
                    fromStatus != null ? AssetStatus.valueOf(fromStatus) : null,
                    toStatus != null ? AssetStatus.valueOf(toStatus) : null,
                    detail
            );
        }
    }

    /**
     * Internal Persistence Mapper ensuring strict isolation between Document and Domain.
     */
    public static final class AssetPersistenceMapper {

        private AssetPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static AssetDocument toDocument(Asset asset) {
            AssetDocument doc = new AssetDocument();
            doc.setId(asset.getId());
            doc.setOrganisationId(asset.getOrganisationId());
            doc.setName(asset.getName());
            doc.setCategory(asset.getCategory().name());
            doc.setSerialNumber(asset.getSerialNumber());
            doc.setSpecifications(new LinkedHashMap<>(asset.getSpecifications()));
            doc.setStatus(asset.getStatus().name());
            doc.setAssignedToUserId(asset.getAssignedToUserId());
            doc.setLocationId(asset.getLocationId());
            doc.setCreatedAt(asset.getCreatedAt());
            doc.setUpdatedAt(asset.getUpdatedAt());
            doc.setAuditTrail(asset.getAuditTrail().stream()
                    .map(AuditEntryDocument::fromDomain)
                    .collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Asset toDomain(AssetDocument doc) {
            AssetStatus status = doc.getStatus() != null ? AssetStatus.valueOf(doc.getStatus()) : AssetStatus.PROVISIONED;
            List<AssetAuditEntry> trail = doc.getAuditTrail() != null
                    ? doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList())
                    : new ArrayList<>();

            return Asset.reconstitute(
                    doc.getId(),
                    doc.getOrganisationId(),
                    doc.getName(),
                    AssetCategory.valueOf(doc.getCategory()),
                    doc.getSerialNumber(),
                    doc.getSpecifications(),
                    status,
                    doc.getAssignedToUserId(),
                    doc.getLocationId(),
                    doc.getCreatedAt(),
                    doc.getUpdatedAt(),
                    trail
            );
        }
    }
}
