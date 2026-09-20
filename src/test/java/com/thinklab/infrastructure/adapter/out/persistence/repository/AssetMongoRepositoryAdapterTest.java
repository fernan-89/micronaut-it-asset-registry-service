package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument.AssetPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AssetMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<AssetDocument> mongoCollection;

    private AssetMongoRepositoryAdapter adapter;
    private UUID organisationId;
    private UUID assetId;
    private Asset asset;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("thinklab_asset_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("assets", AssetDocument.class)).thenReturn(mongoCollection);
        when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);
        adapter = new AssetMongoRepositoryAdapter(mongoClient);

        organisationId = UUID.randomUUID();
        assetId = UUID.randomUUID();
        asset = Asset.createNew(assetId, organisationId, "Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77",
                Map.of("ports", "48"), "ops-admin");
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private AssetAuditEntry entry() {
        return asset.markReady("ops-admin");
    }

    @Test
    @DisplayName("create should insert the mapped document and emit the aggregate")
    void createSuccess() {
        when(mongoCollection.insertOne(any(AssetDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.create(asset))
                .expectNextMatches(saved -> saved.getId().equals(assetId))
                .verifyComplete();

        ArgumentCaptor<AssetDocument> captor = ArgumentCaptor.forClass(AssetDocument.class);
        verify(mongoCollection).insertOne(captor.capture());
        assertEquals("PROVISIONED", captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getAuditTrail().size());
    }

    @Test
    @DisplayName("create should propagate a driver failure")
    void createFailure() {
        when(mongoCollection.insertOne(any(AssetDocument.class))).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(adapter.create(asset)).expectErrorMessage("mongo down").verify();
    }

    @Test
    @DisplayName("findById should map the document back to the aggregate")
    void findByIdSuccess() {
        FindPublisher<AssetDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(AssetPersistenceMapper.toDocument(asset)));

        StepVerifier.create(adapter.findById(assetId))
                .expectNextMatches(found -> found.getId().equals(assetId)
                        && found.getStatus() == AssetStatus.PROVISIONED
                        && found.getAuditTrail().size() == 1)
                .verifyComplete();
    }

    @Test
    @DisplayName("findById should complete empty when the asset does not exist")
    void findByIdEmpty() {
        FindPublisher<AssetDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(assetId)).verifyComplete();
    }

    @Test
    @DisplayName("findAllByOrganisationId should always filter by tenant and add status/category when given")
    void findAllFilters() {
        FindPublisher<AssetDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<AssetDocument> subscriber = invocation.getArgument(0);
            Flux.just(AssetPersistenceMapper.toDocument(asset)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(organisationId, AssetStatus.READY, AssetCategory.LAPTOP))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(rendered.contains("READY"));
        assertTrue(rendered.contains("LAPTOP"));
    }

    @Test
    @DisplayName("findAllByOrganisationId without optional filters should only constrain the tenant")
    void findAllTenantOnly() {
        FindPublisher<AssetDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<AssetDocument> subscriber = invocation.getArgument(0);
            Flux.<AssetDocument>empty().subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(organisationId, null, null)).verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(!rendered.contains("status") && !rendered.contains("category"));
    }

    @Test
    @DisplayName("updateBasicInfo should $set name/specs/updatedAt and $push the audit entry atomically")
    void updateBasicInfo() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        AssetAuditEntry auditEntry = asset.updateInfo("Core Switch v2", Map.of("ports", "96"), "tech");

        StepVerifier.create(adapter.updateBasicInfo(assetId, "Core Switch v2", Map.of("ports", "96"), auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("Core Switch v2", doc.getDocument("$set").getString("name").getValue());
        assertTrue(doc.getDocument("$set").containsKey("specifications"));
        assertTrue(doc.getDocument("$set").containsKey("updatedAt"));
        assertEquals("UPDATED", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("updateAssignment should $set holder and location and $push the audit entry")
    void updateAssignment() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        UUID holder = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        AssetAuditEntry auditEntry = asset.assign(holder, location, "tech");

        StepVerifier.create(adapter.updateAssignment(assetId, holder, location, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertTrue(doc.getDocument("$set").containsKey("assignedToUserId"));
        assertTrue(doc.getDocument("$set").containsKey("locationId"));
        assertEquals("ASSIGNED", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("updateStatus should $set status and $push a STATUS_CHANGED audit entry")
    void updateStatus() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        AssetAuditEntry auditEntry = entry();

        StepVerifier.create(adapter.updateStatus(assetId, AssetStatus.READY, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("READY", doc.getDocument("$set").getString("status").getValue());
        BsonDocument pushed = doc.getDocument("$push").getDocument("auditTrail");
        assertEquals("STATUS_CHANGED", pushed.getString("action").getValue());
        assertEquals("PROVISIONED", pushed.getString("fromStatus").getValue());
        assertEquals("READY", pushed.getString("toStatus").getValue());
    }

    @Test
    @DisplayName("every partial update should fail with AssetNotFoundException when no document matches")
    void updatesFailWhenNothingMatches() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        AssetAuditEntry auditEntry = entry();

        StepVerifier.create(adapter.updateStatus(assetId, AssetStatus.READY, auditEntry))
                .expectError(AssetNotFoundException.class).verify();
        StepVerifier.create(adapter.updateBasicInfo(assetId, "n", Map.of(), auditEntry))
                .expectError(AssetNotFoundException.class).verify();
        StepVerifier.create(adapter.updateAssignment(assetId, null, null, auditEntry))
                .expectError(AssetNotFoundException.class).verify();
    }

    @Test
    @DisplayName("existsByOrganisationIdAndSerialNumber should be true when the count is positive")
    void existsTrue() {
        when(mongoCollection.countDocuments(any(Bson.class), any(CountOptions.class))).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.existsByOrganisationIdAndSerialNumber(organisationId, "SN-77"))
                .expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("existsByOrganisationIdAndSerialNumber should be false when the count is zero or the publisher is empty")
    void existsFalse() {
        when(mongoCollection.countDocuments(any(Bson.class), any(CountOptions.class)))
                .thenReturn(Mono.just(0L))
                .thenReturn(Mono.empty());

        StepVerifier.create(adapter.existsByOrganisationIdAndSerialNumber(organisationId, "SN-1")).expectNext(false).verifyComplete();
        StepVerifier.create(adapter.existsByOrganisationIdAndSerialNumber(organisationId, "SN-1")).expectNext(false).verifyComplete();
    }
}
