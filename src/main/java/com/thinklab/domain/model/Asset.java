package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAssetStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Core Domain Model representing the Asset Aggregate Root.
 *
 * <p><b>BIAN Alignment (ADR-013):</b> This is the Control Record of the {@code it-asset-registry}
 * Service Domain — the authoritative inventory record of a physical or logical IT asset, scoped to
 * an Organisation from the Party Reference Data Directory.
 *
 * <p><b>Forensic Audit Ledger (AST-02):</b> every mutation appends an immutable
 * {@link AssetAuditEntry} to the aggregate's trail. Mutating behaviors return the entry they
 * produced so the application layer can persist it atomically with the granular update.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class Asset {

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private final AssetCategory category;
    private final String serialNumber;
    private Map<String, String> specifications;
    private AssetStatus status;
    private UUID assignedToUserId;
    private UUID locationId;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AssetAuditEntry> auditTrail;

    private Asset(UUID id, UUID organisationId, String name, AssetCategory category, String serialNumber,
                  Map<String, String> specifications, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.category = category;
        this.serialNumber = serialNumber;
        this.specifications = copySpecifications(specifications);
        this.status = AssetStatus.PROVISIONED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new AssetAuditEntry(this.createdAt, "INITIATED", executor, null, AssetStatus.PROVISIONED,
                "Asset registered in the inventory."));
    }

    private Asset(UUID id, UUID organisationId, String name, AssetCategory category, String serialNumber,
                  Map<String, String> specifications, AssetStatus status, UUID assignedToUserId, UUID locationId,
                  Instant createdAt, Instant updatedAt, List<AssetAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.category = category;
        this.serialNumber = serialNumber;
        this.specifications = copySpecifications(specifications);
        this.status = status != null ? status : AssetStatus.PROVISIONED;
        this.assignedToUserId = assignedToUserId;
        this.locationId = locationId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /**
     * Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). The UUID must
     * be provided by the orchestration layer after calling the Hash Token Registry.
     */
    public static Asset createNew(UUID id, UUID organisationId, String name, AssetCategory category,
                                  String serialNumber, Map<String, String> specifications, String executor) {
        if (id == null || organisationId == null || category == null) {
            throw new IllegalArgumentException("ID, Organisation ID, and Category are mandatory for Asset creation.");
        }
        if (name == null || name.isBlank() || serialNumber == null || serialNumber.isBlank()) {
            throw new IllegalArgumentException("Name and Serial Number are mandatory for Asset creation.");
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Asset creation.");
        }
        return new Asset(id, organisationId, name, category, serialNumber, specifications, executor);
    }

    /**
     * Reconstitutes an existing Asset aggregate from the persistence layer.
     */
    public static Asset reconstitute(UUID id, UUID organisationId, String name, AssetCategory category,
                                     String serialNumber, Map<String, String> specifications, AssetStatus status,
                                     UUID assignedToUserId, UUID locationId, Instant createdAt, Instant updatedAt,
                                     List<AssetAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || category == null || serialNumber == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name, Category, and Serial Number are mandatory to reconstitute an Asset.");
        }
        return new Asset(id, organisationId, name, category, serialNumber, specifications, status,
                assignedToUserId, locationId, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors (State Mutations) ---

    /**
     * Behavior Qualifier: {@code update}. Replaces the descriptive fields of an asset that is still
     * part of the active inventory.
     */
    public AssetAuditEntry updateInfo(String newName, Map<String, String> newSpecifications, String executor) {
        requireNotDecommissioned("update");
        requireExecutor(executor);
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Name cannot be empty.");
        }
        this.name = newName;
        this.specifications = copySpecifications(newSpecifications);
        return record("UPDATED", executor, "Descriptive information updated.");
    }

    /**
     * Behavior Qualifier: {@code assignment/update}. Binds the asset to a holder and/or a location.
     * A DEPLOYED asset must always keep a location.
     */
    public AssetAuditEntry assign(UUID newAssignedToUserId, UUID newLocationId, String executor) {
        requireNotDecommissioned("assign");
        requireExecutor(executor);
        if (this.status == AssetStatus.DEPLOYED && newLocationId == null) {
            throw new InvalidAssetStatusException(
                    "Policy Violation: a DEPLOYED asset must keep a location; return it to READY before clearing the location.");
        }
        this.assignedToUserId = newAssignedToUserId;
        this.locationId = newLocationId;
        return record("ASSIGNED", executor, String.format("Assignment updated. holder=[%s] location=[%s]",
                newAssignedToUserId, newLocationId));
    }

    /**
     * Behavior Qualifier: {@code control}. Transitions the asset to the given target status, enforcing
     * the {@link AssetStatus} state machine and the deployment policy.
     */
    public AssetAuditEntry changeStatus(AssetStatus newStatus, String executor) {
        Objects.requireNonNull(newStatus, "Status cannot be null.");
        requireExecutor(executor);
        this.status.validateTransitionTo(newStatus);
        if (newStatus == AssetStatus.DEPLOYED && this.locationId == null) {
            throw new InvalidAssetStatusException(
                    "Policy Violation: an asset cannot be DEPLOYED without a location. Assign a location first.");
        }
        AssetStatus previous = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();
        AssetAuditEntry entry = new AssetAuditEntry(this.updatedAt, "STATUS_CHANGED", executor, previous, newStatus,
                String.format("Lifecycle transition %s -> %s.", previous, newStatus));
        this.auditTrail.add(entry);
        return entry;
    }

    public AssetAuditEntry markReady(String executor) { return changeStatus(AssetStatus.READY, executor); }

    public AssetAuditEntry deploy(String executor) { return changeStatus(AssetStatus.DEPLOYED, executor); }

    public AssetAuditEntry startMaintenance(String executor) { return changeStatus(AssetStatus.MAINTENANCE, executor); }

    /**
     * Terminal transition (BIAN Behavior Qualifier: {@code control/decommission}). No physical DELETE
     * exists in this Service Domain — the asset is never removed from the ledger, only closed.
     */
    public AssetAuditEntry decommission(String executor) { return changeStatus(AssetStatus.DECOMMISSIONED, executor); }

    // --- Internal helpers ---

    private AssetAuditEntry record(String action, String executor, String detail) {
        this.updatedAt = Instant.now();
        AssetAuditEntry entry = new AssetAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireNotDecommissioned(String operation) {
        if (this.status == AssetStatus.DECOMMISSIONED) {
            throw new InvalidAssetStatusException(String.format(
                    "Compliance Violation: cannot %s a DECOMMISSIONED asset; the lifecycle is terminal.", operation));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Asset mutations.");
        }
    }

    private static Map<String, String> copySpecifications(Map<String, String> specifications) {
        return specifications == null ? new LinkedHashMap<>() : new LinkedHashMap<>(specifications);
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public AssetCategory getCategory() { return category; }
    public String getSerialNumber() { return serialNumber; }
    public Map<String, String> getSpecifications() { return Collections.unmodifiableMap(specifications); }
    public AssetStatus getStatus() { return status; }
    public UUID getAssignedToUserId() { return assignedToUserId; }
    public UUID getLocationId() { return locationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AssetAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum AssetCategory {
        LAPTOP, DESKTOP, SERVER, NETWORK_DEVICE, STORAGE_ARRAY, PERIPHERAL,
        MOBILE_DEVICE, IOT_SENSOR, VIRTUAL_MACHINE, SOFTWARE_LICENSE
    }

    /**
     * Immutable forensic ledger entry (AST-02).
     *
     * @param fromStatus status before the action ({@code null} for the initiating entry)
     * @param toStatus   status after the action (equal to {@code fromStatus} for non-transition actions)
     */
    public record AssetAuditEntry(Instant occurredAt, String action, String executor,
                                  AssetStatus fromStatus, AssetStatus toStatus, String detail) {}

    /**
     * Formal lifecycle state machine for the Asset Control Record, mirroring the
     * {@code HashStatus}/{@code OrganisationStatus} pattern (ADR-013).
     *
     * <pre>
     * PROVISIONED -> READY -> DEPLOYED <-> MAINTENANCE
     *                  ^          |            |
     *                  +----------+------------+   (return to stock)
     * any non-terminal -> DECOMMISSIONED (terminal)
     * </pre>
     */
    public enum AssetStatus {
        PROVISIONED, READY, DEPLOYED, MAINTENANCE, DECOMMISSIONED;

        /**
         * Validates if the transition from the current state to the target state is legally permitted.
         *
         * @throws InvalidAssetStatusException (HTTP 409) if the transition violates business compliance
         *                                     rules or is unnecessarily idempotent.
         */
        public void validateTransitionTo(AssetStatus targetStatus) {
            Objects.requireNonNull(targetStatus, "Target AssetStatus must not be null for transition validation.");

            if (this == targetStatus) {
                throw new InvalidAssetStatusException(String.format(
                        "Idempotency Violation: The Asset is already in the [%s] state.", this));
            }
            if (!canTransitionTo(targetStatus)) {
                throw new InvalidAssetStatusException(String.format(
                        "Compliance Violation: Illegal state transition from [%s] to [%s].", this, targetStatus));
            }
        }

        public boolean canTransitionTo(AssetStatus targetStatus) {
            if (targetStatus == null) {
                return false;
            }
            return switch (this) {
                case PROVISIONED -> targetStatus == READY || targetStatus == DECOMMISSIONED;
                case READY -> targetStatus == DEPLOYED || targetStatus == DECOMMISSIONED;
                case DEPLOYED -> targetStatus == MAINTENANCE || targetStatus == READY || targetStatus == DECOMMISSIONED;
                case MAINTENANCE -> targetStatus == DEPLOYED || targetStatus == READY || targetStatus == DECOMMISSIONED;
                case DECOMMISSIONED -> false;
            };
        }
    }
}
