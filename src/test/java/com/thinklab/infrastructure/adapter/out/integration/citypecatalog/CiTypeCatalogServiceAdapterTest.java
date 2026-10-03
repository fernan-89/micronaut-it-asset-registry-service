package com.thinklab.infrastructure.adapter.out.integration.citypecatalog;

import com.thinklab.domain.exception.CiTypeCatalogUnavailableException;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.infrastructure.adapter.out.integration.citypecatalog.CiTypeCatalogServiceAdapter.ActiveSchemaApiResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CiTypeCatalogServiceAdapterTest {

    @Mock private CiTypeCatalogApiClient apiClient;

    private CiTypeCatalogServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CiTypeCatalogServiceAdapter(apiClient, false);
    }

    @Test
    @DisplayName("fetchActiveSchema returns the schema when an ACTIVE TypeDefinition exists")
    void fetchActiveSchemaFound() {
        String schema = "{\"type\":\"object\"}";
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.just(
                new ActiveSchemaApiResponse(UUID.randomUUID(), "NETWORK_DEVICE", schema, Instant.now())));

        StepVerifier.create(adapter.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectNext(Optional.of(schema))
                .verifyComplete();
    }

    @Test
    @DisplayName("fetchActiveSchema fails open (empty) when the catalog has no ACTIVE schema configured (404)")
    void fetchActiveSchemaNotConfigured() {
        HttpClientResponseException notFound = new HttpClientResponseException("Not Found",
                HttpResponse.status(HttpStatus.NOT_FOUND));
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.error(notFound));

        StepVerifier.create(adapter.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    @DisplayName("fetchActiveSchema fails open (empty) on a non-404 HTTP error from the catalog")
    void fetchActiveSchemaOtherHttpError() {
        HttpClientResponseException serverError = new HttpClientResponseException("Internal Server Error",
                HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR));
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.error(serverError));

        StepVerifier.create(adapter.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    @DisplayName("fetchActiveSchema fails open (empty) when the catalog is entirely unreachable")
    void fetchActiveSchemaUnreachable() {
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        StepVerifier.create(adapter.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    @DisplayName("fail-closed: a non-404 HTTP error from the catalog refuses the write (CiTypeCatalogUnavailableException)")
    void failClosedOtherHttpError() {
        CiTypeCatalogServiceAdapter strict = new CiTypeCatalogServiceAdapter(apiClient, true);
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.error(new HttpClientResponseException("Bad Gateway",
                HttpResponse.status(HttpStatus.BAD_GATEWAY))));

        StepVerifier.create(strict.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(CiTypeCatalogUnavailableException.class, error);
                    assertEquals("ERR-AST-00503", ((CiTypeCatalogUnavailableException) error).getErrorCode());
                })
                .verify();
    }

    @Test
    @DisplayName("fail-closed: an unreachable catalog refuses the write (CiTypeCatalogUnavailableException)")
    void failClosedUnreachable() {
        CiTypeCatalogServiceAdapter strict = new CiTypeCatalogServiceAdapter(apiClient, true);
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        StepVerifier.create(strict.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectError(CiTypeCatalogUnavailableException.class)
                .verify();
    }

    @Test
    @DisplayName("fail-closed: a 404 (no schema configured) still skips validation")
    void failClosedStillSkipsWhenNotConfigured() {
        CiTypeCatalogServiceAdapter strict = new CiTypeCatalogServiceAdapter(apiClient, true);
        when(apiClient.fetchActiveSchema(any(), any())).thenReturn(Mono.error(new HttpClientResponseException("Not Found",
                HttpResponse.status(HttpStatus.NOT_FOUND))));

        StepVerifier.create(strict.fetchActiveSchema(UUID.randomUUID(), AssetCategory.NETWORK_DEVICE))
                .expectNext(Optional.empty())
                .verifyComplete();
    }
}
