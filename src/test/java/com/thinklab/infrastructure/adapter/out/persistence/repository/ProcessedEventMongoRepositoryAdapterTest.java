package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ProcessedEventMongoRepositoryAdapterTest {

    private final MongoClient client = mock(MongoClient.class);
    private final MongoDatabase database = mock(MongoDatabase.class);
    private final MongoCollection<Document> collection = mock(MongoCollection.class);

    @BeforeEach
    void setUp() {
        when(client.getDatabase("assets_db")).thenReturn(database);
        when(client.getDatabase(AssetMongoRepositoryAdapter.DEFAULT_DATABASE)).thenReturn(database);
        when(database.getCollection("processed_events")).thenReturn(collection);
    }

    private ProcessedEventMongoRepositoryAdapter adapter() {
        return new ProcessedEventMongoRepositoryAdapter(client, "mongodb://mongo:27017/assets_db");
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "E" + code, new BsonDocument()), new ServerAddress());
    }

    @Test
    @DisplayName("isProcessed reflects whether the key is recorded")
    void isProcessed() {
        when(collection.countDocuments(any(Bson.class), any(CountOptions.class))).thenReturn(Mono.just(1L), Mono.just(0L), Mono.empty());

        StepVerifier.create(adapter().isProcessed("repair-started:a")).expectNext(true).verifyComplete();
        StepVerifier.create(adapter().isProcessed("repair-started:b")).expectNext(false).verifyComplete();
        StepVerifier.create(adapter().isProcessed("repair-started:c")).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("markProcessed inserts the key; an already recorded key is not an error, other write errors are")
    void markProcessed() {
        when(collection.insertOne(any(Document.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonString("repair-started:a"))))
                .thenReturn(Mono.error(writeError(11000)))
                .thenReturn(Mono.error(writeError(2)))
                .thenReturn(Mono.error(new IllegalStateException("not a write error")));

        StepVerifier.create(adapter().markProcessed("repair-started:a")).verifyComplete();
        StepVerifier.create(adapter().markProcessed("repair-started:a")).verifyComplete();
        StepVerifier.create(adapter().markProcessed("repair-started:b")).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter().markProcessed("repair-started:c")).expectError(IllegalStateException.class).verify();
    }

    @Test
    @DisplayName("a URI without a database uses the service default")
    void defaultDatabase() {
        when(collection.countDocuments(any(Bson.class), any(CountOptions.class))).thenReturn(Mono.just(0L));

        new ProcessedEventMongoRepositoryAdapter(client, "mongodb://mongo:27017").isProcessed("k").block();

        verify(client).getDatabase(AssetMongoRepositoryAdapter.DEFAULT_DATABASE);
    }

    @Test
    @DisplayName("guards")
    void guards() {
        assertThrows(NullPointerException.class, () -> new ProcessedEventMongoRepositoryAdapter(null, "mongodb://h/db"));
        assertThrows(NullPointerException.class, () -> new ProcessedEventMongoRepositoryAdapter(client, null));
        assertThrows(NullPointerException.class, () -> adapter().isProcessed(null));
        assertThrows(NullPointerException.class, () -> adapter().markProcessed(null));
    }
}
