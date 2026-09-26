package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.exception.DuplicateAssetException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.domain.repository.AssetRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Asset aggregate through {@link AssetRepository} against a real MongoDB: the POJO codec with the
 * specifications map and the forensic audit ledger, every partial update appending to that ledger in order,
 * tenant-scoped filtering, the serial-number duplicate check and its unique index, not-found handling, and
 * the database taken from {@code mongodb.uri}.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_asset_registry_it";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject
    AssetRepository assets;

    @Inject
    MongoClient mongoClient;

    private static Asset newAsset(UUID organisationId, AssetCategory category) {
        return Asset.createNew(UUID.randomUUID(), organisationId, "ThinkPad P1", category, "SN-" + UUID.randomUUID(),
                Map.of("cpu", "i9", "ram", "64GB"), "it-operator");
    }

    private static AssetAuditEntry audit(String action, AssetStatus from, AssetStatus to) {
        return new AssetAuditEntry(Instant.now().truncatedTo(ChronoUnit.MILLIS), action, "it-operator", from, to, action + " detail");
    }

    @Test
    @DisplayName("a created asset is read back with its specifications and initial ledger entry")
    void createAndFind() {
        Asset created = assets.create(newAsset(UUID.randomUUID(), AssetCategory.LAPTOP)).block();

        Asset found = assets.findById(created.getId()).block();

        assertEquals(created.getSerialNumber(), found.getSerialNumber());
        assertEquals(AssetCategory.LAPTOP, found.getCategory());
        assertEquals(AssetStatus.PROVISIONED, found.getStatus());
        assertEquals(Map.of("cpu", "i9", "ram", "64GB"), found.getSpecifications());
        assertEquals(created.getAuditTrail().size(), found.getAuditTrail().size());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    @DisplayName("writes land in the database named by mongodb.uri")
    void usesTheConfiguredDatabase() {
        Asset created = assets.create(newAsset(UUID.randomUUID(), AssetCategory.SERVER)).block();

        Document stored = Mono.from(mongoClient.getDatabase(DATABASE).getCollection("assets")
                .find(Filters.eq("_id", created.getId())).first()).block();

        assertNotNull(stored, "asset not found in " + DATABASE);
    }

    @Test
    @DisplayName("each partial update is persisted and appends its entry to the ledger, in order")
    void partialUpdatesAppendToTheLedger() {
        Asset created = assets.create(newAsset(UUID.randomUUID(), AssetCategory.LAPTOP)).block();
        int initial = created.getAuditTrail().size();
        UUID user = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        AssetAuditEntry rename = audit("UPDATE_INFO", AssetStatus.PROVISIONED, AssetStatus.PROVISIONED);
        AssetAuditEntry ready = audit("STATUS", AssetStatus.PROVISIONED, AssetStatus.READY);
        AssetAuditEntry assign = audit("ASSIGN", AssetStatus.READY, AssetStatus.READY);

        assets.updateBasicInfo(created.getId(), "ThinkPad P1 Gen 7", Map.of("cpu", "Ultra 9"), rename).block();
        assets.updateStatus(created.getId(), AssetStatus.READY, ready).block();
        assets.updateAssignment(created.getId(), user, location, assign).block();

        Asset found = assets.findById(created.getId()).block();
        assertEquals("ThinkPad P1 Gen 7", found.getName());
        assertEquals(Map.of("cpu", "Ultra 9"), found.getSpecifications());
        assertEquals(AssetStatus.READY, found.getStatus());
        assertEquals(user, found.getAssignedToUserId());
        assertEquals(location, found.getLocationId());
        assertEquals(List.of(rename, ready, assign), found.getAuditTrail().subList(initial, initial + 3));
    }

    @Test
    @DisplayName("listing is tenant-scoped and honours the optional status and category filters")
    void listingFilters() {
        UUID organisation = UUID.randomUUID();
        Asset laptop = assets.create(newAsset(organisation, AssetCategory.LAPTOP)).block();
        Asset server = assets.create(newAsset(organisation, AssetCategory.SERVER)).block();
        assets.create(newAsset(UUID.randomUUID(), AssetCategory.LAPTOP)).block();
        assets.updateStatus(server.getId(), AssetStatus.READY, audit("STATUS", AssetStatus.PROVISIONED, AssetStatus.READY)).block();

        assertEquals(Set.of(laptop.getId(), server.getId()), ids(assets.findAllByOrganisationId(organisation, null, null).collectList().block()));
        assertEquals(Set.of(server.getId()), ids(assets.findAllByOrganisationId(organisation, AssetStatus.READY, null).collectList().block()));
        assertEquals(Set.of(laptop.getId()), ids(assets.findAllByOrganisationId(organisation, null, AssetCategory.LAPTOP).collectList().block()));
        assertEquals(Set.of(), ids(assets.findAllByOrganisationId(organisation, AssetStatus.READY, AssetCategory.LAPTOP).collectList().block()));
    }

    @Test
    @DisplayName("the serial-number check is scoped to the tenant")
    void serialNumberCheck() {
        Asset created = assets.create(newAsset(UUID.randomUUID(), AssetCategory.LAPTOP)).block();

        assertTrue(assets.existsByOrganisationIdAndSerialNumber(created.getOrganisationId(), created.getSerialNumber()).block());
        assertFalse(assets.existsByOrganisationIdAndSerialNumber(UUID.randomUUID(), created.getSerialNumber()).block());
        assertFalse(assets.existsByOrganisationIdAndSerialNumber(created.getOrganisationId(), "SN-unknown").block());
    }

    @Test
    @DisplayName("an unknown asset is empty on read and AssetNotFoundException on update")
    void notFound() {
        UUID unknown = UUID.randomUUID();

        assertNull(assets.findById(unknown).block());
        assertThrows(AssetNotFoundException.class, () -> assets.updateStatus(unknown, AssetStatus.READY,
                audit("STATUS", AssetStatus.PROVISIONED, AssetStatus.READY)).block());
    }

    @Test
    @DisplayName("the unique (organisationId, serialNumber) index exists")
    void serialNumberIndexExists() {
        assets.create(newAsset(UUID.randomUUID(), AssetCategory.LAPTOP)).block();

        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("assets").listIndexes()).collectList().block();

        assertTrue(indexes.stream().anyMatch(index -> new Document("organisationId", 1).append("serialNumber", 1).equals(index.get("key", Document.class))
                && Boolean.TRUE.equals(index.getBoolean("unique"))), () -> "assets: " + indexes);
    }

    @Test
    @DisplayName("concurrent creations with the same serial number: exactly one wins, the others are DuplicateAssetException")
    void concurrentDuplicateCreation() {
        UUID organisation = UUID.randomUUID();
        String serial = "SN-" + UUID.randomUUID();

        List<String> outcomes = Flux.range(0, 8)
                .flatMap(i -> assets.create(Asset.createNew(UUID.randomUUID(), organisation, "ThinkPad P1", AssetCategory.LAPTOP, serial,
                                Map.of(), "it-operator"))
                        .map(created -> "created")
                        .onErrorResume(DuplicateAssetException.class, e -> Mono.just("duplicate"))
                        .subscribeOn(Schedulers.parallel()))
                .collectList().block();

        assertEquals(1, outcomes.stream().filter("created"::equals).count(), () -> "outcomes: " + outcomes);
        assertEquals(7, outcomes.stream().filter("duplicate"::equals).count(), () -> "outcomes: " + outcomes);
        assertEquals(1, assets.findAllByOrganisationId(organisation, null, null).count().block());
    }

    private static Set<UUID> ids(List<Asset> list) {
        return list.stream().map(Asset::getId).collect(Collectors.toSet());
    }
}
