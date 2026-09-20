package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.domain.repository.AssetRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument.AssetPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument.AuditEntryDocument;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter.
 * Implements the pure Domain Port using the low-level Reactive Streams MongoDB Driver and strictly
 * enforces Partial State Mutations: every transition is a single atomic {@code $set} + {@code $push}
 * that also appends the forensic audit entry, so state and ledger can never diverge.
 */
@Singleton
public class AssetMongoRepositoryAdapter implements AssetRepository {

    private static final Logger log = LoggerFactory.getLogger(AssetMongoRepositoryAdapter.class);

    static final String DATABASE_NAME = "thinklab_asset_db";
    static final String COLLECTION_NAME = "assets";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_AUDIT_TRAIL = "auditTrail";

    /**
     * The MongoDB driver's default codec registry has no codec for arbitrary POJOs such as
     * {@link AssetDocument}. Without a {@link PojoCodecProvider} every read/write fails with
     * {@code CodecConfigurationException} (lesson learned from the Party Reference Data Directory rollout).
     */
    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;

    public AssetMongoRepositoryAdapter(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    private MongoCollection<AssetDocument> getCollection() {
        return mongoClient.getDatabase(DATABASE_NAME)
                .getCollection(COLLECTION_NAME, AssetDocument.class)
                .withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Asset> create(Asset asset) {
        log.debug("[PERSISTENCE] Monolithic create for Asset Aggregate: {}", asset.getId());

        AssetDocument document = AssetPersistenceMapper.toDocument(asset);

        return Mono.from(getCollection().insertOne(document))
                .doOnSuccess(result -> log.debug("[PERSISTENCE] Aggregate successfully created in MongoDB"))
                .map(result -> asset);
    }

    @Override
    public Mono<Asset> findById(UUID id) {
        log.debug("[PERSISTENCE] Fetching Asset Aggregate by ID: {}", id);

        return Mono.from(getCollection().find(Filters.eq(FIELD_ID, id)).first())
                .map(AssetPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Asset> findAllByOrganisationId(UUID organisationId, AssetStatus status, AssetCategory category) {
        log.debug("[PERSISTENCE] Fetching Assets for organisation {} status {} category {}", organisationId, status, category);

        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("organisationId", organisationId));
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (category != null) {
            filters.add(Filters.eq("category", category.name()));
        }

        return Flux.from(getCollection().find(Filters.and(filters)))
                .map(AssetPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateBasicInfo(UUID id, String name, Map<String, String> specifications, AssetAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updateBasicInfo for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("name", name),
                Updates.set("specifications", new LinkedHashMap<>(specifications)),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Void> updateAssignment(UUID id, UUID assignedToUserId, UUID locationId, AssetAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updateAssignment for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("assignedToUserId", assignedToUserId),
                Updates.set("locationId", locationId),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Void> updateStatus(UUID id, AssetStatus status, AssetAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updateStatus for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("status", status.name()),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Boolean> existsByOrganisationIdAndSerialNumber(UUID organisationId, String serialNumber) {
        Bson filter = Filters.and(
                Filters.eq("organisationId", organisationId),
                Filters.eq("serialNumber", serialNumber)
        );

        return Mono.from(getCollection().countDocuments(filter, new com.mongodb.client.model.CountOptions().limit(1)))
                .map(count -> count > 0)
                .defaultIfEmpty(false);
    }

    /**
     * Helper that executes a partial update and translates a zero-match result into a domain error.
     */
    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(getCollection().updateOne(Filters.eq(FIELD_ID, id), update))
                .flatMap(result -> {
                    if (result.getMatchedCount() == 0) {
                        return Mono.error(new AssetNotFoundException(id));
                    }
                    return Mono.empty();
                });
    }
}
