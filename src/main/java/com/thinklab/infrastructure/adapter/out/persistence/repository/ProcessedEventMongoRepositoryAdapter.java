package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.repository.ProcessedEventRepository;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.Document;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.Objects;

/**
 * {@link ProcessedEventRepository} on MongoDB: one document per handled event, keyed by {@code _id}, so the
 * primary-key index makes both the check and the idempotent insert cheap and race-free.
 */
@Singleton
public class ProcessedEventMongoRepositoryAdapter implements ProcessedEventRepository {

    static final String COLLECTION_NAME = "processed_events";

    private final MongoClient mongoClient;
    private final String database;

    public ProcessedEventMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : AssetMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME);
    }

    @Override
    public Mono<Boolean> isProcessed(String eventKey) {
        Objects.requireNonNull(eventKey, "Infrastructure constraint violated: eventKey cannot be null.");
        return Mono.from(collection().countDocuments(Filters.eq("_id", eventKey), new CountOptions().limit(1)))
                .map(count -> count > 0)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> markProcessed(String eventKey) {
        Objects.requireNonNull(eventKey, "Infrastructure constraint violated: eventKey cannot be null.");
        return Mono.from(collection().insertOne(new Document("_id", eventKey).append("processedAt", new Date())))
                .then()
                .onErrorResume(ProcessedEventMongoRepositoryAdapter::isDuplicateKey, e -> Mono.empty());
    }

    static boolean isDuplicateKey(Throwable error) {
        return error instanceof MongoWriteException write && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }
}
