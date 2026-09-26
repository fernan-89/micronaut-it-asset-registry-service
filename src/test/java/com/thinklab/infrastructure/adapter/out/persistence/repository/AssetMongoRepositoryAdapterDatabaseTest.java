package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AssetDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The adapter reads and writes the database named by {@code mongodb.uri}, not a hardcoded one. */
@SuppressWarnings("unchecked")
class AssetMongoRepositoryAdapterDatabaseTest {

    private MongoClient clientServing(String database) {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        MongoCollection<AssetDocument> collection = mock(MongoCollection.class);
        FindPublisher<AssetDocument> find = mock(FindPublisher.class);
        when(client.getDatabase(database)).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("assets", AssetDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(Mono.empty());
        return client;
    }

    @Test
    @DisplayName("the database named in mongodb.uri is the one used")
    void usesTheConfiguredDatabase() {
        MongoClient client = clientServing("inventory");

        new AssetMongoRepositoryAdapter(client, "mongodb://mongo:27017/inventory").findById(UUID.randomUUID()).block();

        verify(client).getDatabase("inventory");
    }

    @Test
    @DisplayName("a URI without a database falls back to the default")
    void fallsBackToTheDefaultDatabase() {
        MongoClient client = clientServing(AssetMongoRepositoryAdapter.DEFAULT_DATABASE);

        new AssetMongoRepositoryAdapter(client, "mongodb://mongo:27017").findById(UUID.randomUUID()).block();

        verify(client).getDatabase(AssetMongoRepositoryAdapter.DEFAULT_DATABASE);
    }

    @Test
    @DisplayName("mongodb.uri is mandatory")
    void uriIsMandatory() {
        assertThrows(NullPointerException.class, () -> new AssetMongoRepositoryAdapter(mock(MongoClient.class), null));
    }
}
