package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAssetStatusException;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTest {

    private static final String EXECUTOR = "ops-admin";

    private UUID id;
    private UUID organisationId;
    private UUID locationId;
    private UUID holderId;
    private Asset asset;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        organisationId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        holderId = UUID.randomUUID();
        asset = Asset.createNew(id, organisationId, "Dell Latitude 7440", AssetCategory.LAPTOP, "SN-0001",
                Map.of("cpu", "i7", "ram", "32GB"), EXECUTOR);
    }

    // ---------------------------------------------------------------- creation

    @Test
    @DisplayName("Should create a new Asset in PROVISIONED state with an INITIATED audit entry")
    void shouldCreateInProvisionedState() {
        assertEquals(id, asset.getId());
        assertEquals(organisationId, asset.getOrganisationId());
        assertEquals("Dell Latitude 7440", asset.getName());
        assertEquals(AssetCategory.LAPTOP, asset.getCategory());
        assertEquals("SN-0001", asset.getSerialNumber());
        assertEquals(AssetStatus.PROVISIONED, asset.getStatus());
        assertEquals("i7", asset.getSpecifications().get("cpu"));
        assertNull(asset.getAssignedToUserId());
        assertNull(asset.getLocationId());
        assertNotNull(asset.getCreatedAt());
        assertEquals(asset.getCreatedAt(), asset.getUpdatedAt());

        assertEquals(1, asset.getAuditTrail().size());
        AssetAuditEntry first = asset.getAuditTrail().get(0);
        assertEquals("INITIATED", first.action());
        assertEquals(EXECUTOR, first.executor());
        assertNull(first.fromStatus());
        assertEquals(AssetStatus.PROVISIONED, first.toStatus());
    }

    @Test
    @DisplayName("Should accept null specifications and expose an empty map")
    void shouldAcceptNullSpecifications() {
        Asset noSpecs = Asset.createNew(id, organisationId, "Rack", AssetCategory.SERVER, "SN-2", null, EXECUTOR);

        assertTrue(noSpecs.getSpecifications().isEmpty());
    }

    @Test
    @DisplayName("Should reject creation when any mandatory field is missing")
    void shouldRejectInvalidCreation() {
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(null, organisationId, "n", AssetCategory.LAPTOP, "s", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, null, "n", AssetCategory.LAPTOP, "s", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, "n", null, "s", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, null, AssetCategory.LAPTOP, "s", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, " ", AssetCategory.LAPTOP, "s", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, "n", AssetCategory.LAPTOP, null, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, "n", AssetCategory.LAPTOP, "", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, "n", AssetCategory.LAPTOP, "s", null, null));
        assertThrows(IllegalArgumentException.class, () -> Asset.createNew(id, organisationId, "n", AssetCategory.LAPTOP, "s", null, "  "));
    }

    @Test
    @DisplayName("Should defensively copy specifications and keep the exposed views read-only")
    void shouldProtectInternalState() {
        Map<String, String> source = new java.util.HashMap<>(Map.of("k", "v"));
        Asset a = Asset.createNew(id, organisationId, "n", AssetCategory.PERIPHERAL, "s", source, EXECUTOR);
        source.put("mutated", "yes");

        assertFalse(a.getSpecifications().containsKey("mutated"));
        assertThrows(UnsupportedOperationException.class, () -> a.getSpecifications().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> a.getAuditTrail().add(null));
    }

    @Test
    @DisplayName("Should expose all ten inventory categories")
    void shouldExposeCategories() {
        assertEquals(10, AssetCategory.values().length);
        assertEquals(AssetCategory.IOT_SENSOR, AssetCategory.valueOf("IOT_SENSOR"));
    }

    // ---------------------------------------------------------------- reconstitution

    @Test
    @DisplayName("Should reconstitute an Asset from persisted state, defaulting missing status/timestamps/trail")
    void shouldReconstitute() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-02-01T00:00:00Z");
        List<AssetAuditEntry> trail = new ArrayList<>();
        trail.add(new AssetAuditEntry(created, "INITIATED", "x", null, AssetStatus.PROVISIONED, "d"));

        Asset restored = Asset.reconstitute(id, organisationId, "n", AssetCategory.SERVER, "s", Map.of("a", "b"),
                AssetStatus.DEPLOYED, holderId, locationId, created, updated, trail);

        assertEquals(AssetStatus.DEPLOYED, restored.getStatus());
        assertEquals(holderId, restored.getAssignedToUserId());
        assertEquals(locationId, restored.getLocationId());
        assertEquals(created, restored.getCreatedAt());
        assertEquals(updated, restored.getUpdatedAt());
        assertEquals(1, restored.getAuditTrail().size());

        Asset defaults = Asset.reconstitute(id, organisationId, "n", AssetCategory.SERVER, "s", null,
                null, null, null, null, null, null);
        assertEquals(AssetStatus.PROVISIONED, defaults.getStatus());
        assertNotNull(defaults.getCreatedAt());
        assertEquals(defaults.getCreatedAt(), defaults.getUpdatedAt());
        assertTrue(defaults.getAuditTrail().isEmpty());
        assertTrue(defaults.getSpecifications().isEmpty());
    }

    @Test
    @DisplayName("Should reject reconstitution without mandatory persisted fields")
    void shouldRejectInvalidReconstitution() {
        assertThrows(IllegalArgumentException.class, () -> Asset.reconstitute(null, organisationId, "n", AssetCategory.LAPTOP, "s", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> Asset.reconstitute(id, null, "n", AssetCategory.LAPTOP, "s", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> Asset.reconstitute(id, organisationId, null, AssetCategory.LAPTOP, "s", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> Asset.reconstitute(id, organisationId, "n", null, "s", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> Asset.reconstitute(id, organisationId, "n", AssetCategory.LAPTOP, null, null, null, null, null, null, null, null));
    }

    // ---------------------------------------------------------------- update / assignment

    @Test
    @DisplayName("Should update descriptive info and append an UPDATED audit entry")
    void shouldUpdateInfo() {
        AssetAuditEntry entry = asset.updateInfo("Dell Latitude 7450", Map.of("ram", "64GB"), "tech-2");

        assertEquals("Dell Latitude 7450", asset.getName());
        assertEquals(Map.of("ram", "64GB"), asset.getSpecifications());
        assertEquals("UPDATED", entry.action());
        assertEquals("tech-2", entry.executor());
        assertEquals(AssetStatus.PROVISIONED, entry.fromStatus());
        assertEquals(AssetStatus.PROVISIONED, entry.toStatus());
        assertEquals(2, asset.getAuditTrail().size());
        assertSame(entry, asset.getAuditTrail().get(1));
    }

    @Test
    @DisplayName("Should reject an update with a blank name or without an executor")
    void shouldRejectInvalidUpdate() {
        assertThrows(IllegalArgumentException.class, () -> asset.updateInfo(" ", Map.of(), EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> asset.updateInfo(null, Map.of(), EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> asset.updateInfo("ok", Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> asset.updateInfo("ok", Map.of(), " "));
        assertEquals(1, asset.getAuditTrail().size());
    }

    @Test
    @DisplayName("Should bind a holder and location and append an ASSIGNED audit entry")
    void shouldAssign() {
        AssetAuditEntry entry = asset.assign(holderId, locationId, EXECUTOR);

        assertEquals(holderId, asset.getAssignedToUserId());
        assertEquals(locationId, asset.getLocationId());
        assertEquals("ASSIGNED", entry.action());
        assertTrue(entry.detail().contains(holderId.toString()));
        assertTrue(entry.detail().contains(locationId.toString()));
    }

    @Test
    @DisplayName("Should clear the assignment when both bindings are null on a non-deployed asset")
    void shouldClearAssignment() {
        asset.assign(holderId, locationId, EXECUTOR);

        asset.assign(null, null, EXECUTOR);

        assertNull(asset.getAssignedToUserId());
        assertNull(asset.getLocationId());
    }

    @Test
    @DisplayName("Should forbid clearing the location of a DEPLOYED asset (422 policy)")
    void shouldForbidClearingLocationWhenDeployed() {
        asset.assign(holderId, locationId, EXECUTOR);
        asset.markReady(EXECUTOR);
        asset.deploy(EXECUTOR);

        InvalidAssetStatusException ex = assertThrows(InvalidAssetStatusException.class, () -> asset.assign(holderId, null, EXECUTOR));

        assertEquals("ERR-AST-00409", ex.getErrorCode());
        assertEquals(locationId, asset.getLocationId());
    }

    @Test
    @DisplayName("Should allow re-assigning a DEPLOYED asset to a different location")
    void shouldAllowMovingDeployedAsset() {
        asset.assign(holderId, locationId, EXECUTOR);
        asset.markReady(EXECUTOR);
        asset.deploy(EXECUTOR);
        UUID newLocation = UUID.randomUUID();

        asset.assign(holderId, newLocation, EXECUTOR);

        assertEquals(newLocation, asset.getLocationId());
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("Should follow the legal path PROVISIONED -> READY -> DEPLOYED -> MAINTENANCE -> DEPLOYED -> DECOMMISSIONED")
    void shouldFollowLegalPath() {
        asset.assign(holderId, locationId, EXECUTOR);

        asset.markReady(EXECUTOR);
        assertEquals(AssetStatus.READY, asset.getStatus());
        asset.deploy(EXECUTOR);
        assertEquals(AssetStatus.DEPLOYED, asset.getStatus());
        asset.startMaintenance(EXECUTOR);
        assertEquals(AssetStatus.MAINTENANCE, asset.getStatus());
        asset.deploy(EXECUTOR);
        assertEquals(AssetStatus.DEPLOYED, asset.getStatus());
        AssetAuditEntry last = asset.decommission("closer");

        assertEquals(AssetStatus.DECOMMISSIONED, asset.getStatus());
        assertEquals("STATUS_CHANGED", last.action());
        assertEquals(AssetStatus.DEPLOYED, last.fromStatus());
        assertEquals(AssetStatus.DECOMMISSIONED, last.toStatus());
        assertEquals("closer", last.executor());
        // INITIATED + ASSIGNED + 5 transitions
        assertEquals(7, asset.getAuditTrail().size());
    }

    @Test
    @DisplayName("Should return a DEPLOYED or MAINTENANCE asset to stock (READY)")
    void shouldReturnToStock() {
        asset.assign(holderId, locationId, EXECUTOR);
        asset.markReady(EXECUTOR);
        asset.deploy(EXECUTOR);
        asset.markReady(EXECUTOR);
        assertEquals(AssetStatus.READY, asset.getStatus());

        asset.deploy(EXECUTOR);
        asset.startMaintenance(EXECUTOR);
        asset.markReady(EXECUTOR);
        assertEquals(AssetStatus.READY, asset.getStatus());
    }

    @Test
    @DisplayName("Should decommission directly from PROVISIONED (never deployed)")
    void shouldDecommissionFromProvisioned() {
        asset.decommission(EXECUTOR);

        assertEquals(AssetStatus.DECOMMISSIONED, asset.getStatus());
    }

    @Test
    @DisplayName("Should refuse to DEPLOY without a location (policy) leaving state untouched")
    void shouldRefuseDeployWithoutLocation() {
        asset.markReady(EXECUTOR);
        int trailSize = asset.getAuditTrail().size();

        InvalidAssetStatusException ex = assertThrows(InvalidAssetStatusException.class, () -> asset.deploy(EXECUTOR));

        assertEquals("ERR-AST-00409", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("location"));
        assertEquals(AssetStatus.READY, asset.getStatus());
        assertEquals(trailSize, asset.getAuditTrail().size());
    }

    @Test
    @DisplayName("Should reject redundant self-transitions as an idempotency violation")
    void shouldRejectSelfTransition() {
        asset.markReady(EXECUTOR);

        InvalidAssetStatusException ex = assertThrows(InvalidAssetStatusException.class, () -> asset.markReady(EXECUTOR));

        assertTrue(ex.getMessage().contains("Idempotency Violation"));
    }

    @Test
    @DisplayName("Should reject PROVISIONED -> DEPLOYED and PROVISIONED -> MAINTENANCE as illegal jumps")
    void shouldRejectIllegalJumps() {
        InvalidAssetStatusException ex = assertThrows(InvalidAssetStatusException.class, () -> asset.deploy(EXECUTOR));
        assertTrue(ex.getMessage().contains("Compliance Violation"));
        assertThrows(InvalidAssetStatusException.class, () -> asset.startMaintenance(EXECUTOR));
        assertEquals(AssetStatus.PROVISIONED, asset.getStatus());
    }

    @Test
    @DisplayName("Should treat DECOMMISSIONED as terminal for every mutation")
    void shouldBlockEverythingAfterDecommission() {
        asset.decommission(EXECUTOR);

        assertThrows(InvalidAssetStatusException.class, () -> asset.markReady(EXECUTOR));
        assertThrows(InvalidAssetStatusException.class, () -> asset.deploy(EXECUTOR));
        assertThrows(InvalidAssetStatusException.class, () -> asset.startMaintenance(EXECUTOR));
        assertThrows(InvalidAssetStatusException.class, () -> asset.decommission(EXECUTOR));
        assertThrows(InvalidAssetStatusException.class, () -> asset.updateInfo("x", Map.of(), EXECUTOR));
        assertThrows(InvalidAssetStatusException.class, () -> asset.assign(holderId, locationId, EXECUTOR));
    }

    @Test
    @DisplayName("Should reject a null target status and a missing executor on control operations")
    void shouldRejectNullArguments() {
        assertThrows(NullPointerException.class, () -> asset.changeStatus(null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> asset.changeStatus(AssetStatus.READY, null));
        assertThrows(IllegalArgumentException.class, () -> asset.assign(holderId, locationId, " "));
        assertEquals(AssetStatus.PROVISIONED, asset.getStatus());
    }

    // ---------------------------------------------------------------- exhaustive FSM matrix

    @TestFactory
    @DisplayName("AssetStatus transition matrix is exhaustive and matches the documented FSM")
    Stream<DynamicTest> transitionMatrix() {
        Map<AssetStatus, Set<AssetStatus>> allowed = Map.of(
                AssetStatus.PROVISIONED, EnumSet.of(AssetStatus.READY, AssetStatus.DECOMMISSIONED),
                AssetStatus.READY, EnumSet.of(AssetStatus.DEPLOYED, AssetStatus.DECOMMISSIONED),
                AssetStatus.DEPLOYED, EnumSet.of(AssetStatus.MAINTENANCE, AssetStatus.READY, AssetStatus.DECOMMISSIONED),
                AssetStatus.MAINTENANCE, EnumSet.of(AssetStatus.DEPLOYED, AssetStatus.READY, AssetStatus.DECOMMISSIONED),
                AssetStatus.DECOMMISSIONED, EnumSet.noneOf(AssetStatus.class)
        );

        return Stream.of(AssetStatus.values()).flatMap(from -> Stream.of(AssetStatus.values()).map(to ->
                DynamicTest.dynamicTest(from + " -> " + to, () -> {
                    boolean expected = allowed.get(from).contains(to);
                    assertEquals(expected, from.canTransitionTo(to));
                    if (expected) {
                        from.validateTransitionTo(to);
                    } else {
                        assertThrows(InvalidAssetStatusException.class, () -> from.validateTransitionTo(to));
                    }
                })));
    }

    @Test
    @DisplayName("canTransitionTo(null) is false and validateTransitionTo(null) is a programming error")
    void shouldHandleNullTargets() {
        assertFalse(AssetStatus.READY.canTransitionTo(null));
        assertThrows(NullPointerException.class, () -> AssetStatus.READY.validateTransitionTo(null));
    }
}
