package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the unique {@code (organisationId, serialNumber)} index on {@code assets} at startup.
 *
 * <p>The use case checks for an existing serial number before inserting, but check-then-insert is not
 * atomic: two concurrent requests for the same serial could both pass the check. The unique index makes
 * the database the arbiter, and {@link AssetMongoRepositoryAdapter} turns the losing insert into the same
 * {@code DuplicateAssetException} (409) the check produces. This adapter uses the driver directly, so the
 * kit's {@code MongoIndexInitializer} (which reads Micronaut Data {@code @Indexes}) does not see it.
 *
 * <p>Fail-open, like the kit's initializer: {@code createIndex} is idempotent; if it fails (for example
 * because duplicates already exist) the error is logged and the application still starts. Turn it off
 * with {@code thinklab.mongo.create-indexes=false}, as unit-test contexts without MongoDB do.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class AssetIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String SERIAL_NUMBER_INDEX = "organisationId_1_serialNumber_1";

    private static final Logger log = LoggerFactory.getLogger(AssetIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public AssetIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    AssetIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : AssetMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        Document keys = new Document("organisationId", 1).append("serialNumber", 1);
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(AssetMongoRepositoryAdapter.COLLECTION_NAME)
                    .createIndex(keys, new IndexOptions().unique(true).name(SERIAL_NUMBER_INDEX))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured unique index [{}] on [{}.{}]", SERIAL_NUMBER_INDEX, database, AssetMongoRepositoryAdapter.COLLECTION_NAME);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", SERIAL_NUMBER_INDEX, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create unique index [{}] on [{}.{}] (existing duplicate serial numbers?): {}",
                    SERIAL_NUMBER_INDEX, database, AssetMongoRepositoryAdapter.COLLECTION_NAME, e.getMessage());
        }
    }
}
