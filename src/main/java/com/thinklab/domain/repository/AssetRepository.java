package com.thinklab.domain.repository;

import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound Port for Asset persistence operations (IT Asset Registry Service Domain).
 * Part of the pure Domain Layer.
 *
 * <p>ARCHITECTURAL RULE: Partial State Mutations (ADR-002). Monolithic save operations are reserved
 * for aggregate creation. Every state transition is a granular update that atomically appends its
 * forensic {@link AssetAuditEntry} to the ledger, so the audit trail can never diverge from state.
 *
 * <p>There is no {@code deleteById} — the terminal {@code control/decommission} Behavior Qualifier
 * transitions the Asset to {@link AssetStatus#DECOMMISSIONED} via {@link #updateStatus}, never a
 * physical deletion (ADR-013).
 */
public interface AssetRepository {

    Mono<Asset> create(Asset asset);

    Mono<Asset> findById(UUID id);

    /**
     * Tenant-scoped listing of Assets belonging to a given Organisation.
     *
     * @param organisationId the tenant boundary
     * @param status         optional status filter ({@code null} = any)
     * @param category       optional category filter ({@code null} = any)
     */
    Flux<Asset> findAllByOrganisationId(UUID organisationId, AssetStatus status, AssetCategory category);

    Mono<Void> updateBasicInfo(UUID id, String name, Map<String, String> specifications, AssetAuditEntry auditEntry);

    Mono<Void> updateAssignment(UUID id, UUID assignedToUserId, UUID locationId, AssetAuditEntry auditEntry);

    Mono<Void> updateStatus(UUID id, AssetStatus status, AssetAuditEntry auditEntry);

    /**
     * Duplicate-prevention check, called explicitly from the use case before creating a new Asset.
     *
     * @return {@code true} if an Asset with this serial number already exists for this Organisation.
     */
    Mono<Boolean> existsByOrganisationIdAndSerialNumber(UUID organisationId, String serialNumber);
}
