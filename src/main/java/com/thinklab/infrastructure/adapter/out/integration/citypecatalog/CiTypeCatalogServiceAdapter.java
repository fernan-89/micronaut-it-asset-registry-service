package com.thinklab.infrastructure.adapter.out.integration.citypecatalog;

import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.port.CiTypeCatalogPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound Adapter for the ci-type-catalog Service Domain. Implements the Domain Port, ensuring that
 * Micronaut-specific HTTP client details do not leak into the Application or Domain layers.
 *
 * <p><b>Fails open on every failure mode (ADR-027):</b> the catalog's own 404 ("no schema configured
 * for this tenant+category") and any other failure (non-404 HTTP error, timeout, connection refused -
 * the catalog service being down entirely) both resolve to {@link Optional#empty()}, never an error
 * signal that would propagate up and fail an Asset write. This is a brand-new, optional, bolt-on
 * dependency for an already-public, core-path service; making {@code initiate}/{@code update} fail
 * hard whenever it hiccups would be a severe availability regression for a dependency that didn't even
 * exist before this journey - the same posture already proven live by {@code NatsStreamInitializer}
 * ("idempotent, fail-open") and {@code RevocationPoller} ("fails open on poll failure") elsewhere on
 * this platform. The accepted trade-off - a brief catalog outage silently skips validation rather than
 * blocking writes - is documented in ADR-027.
 */
@Singleton
public class CiTypeCatalogServiceAdapter implements CiTypeCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CiTypeCatalogServiceAdapter.class);

    private final CiTypeCatalogApiClient apiClient;

    public CiTypeCatalogServiceAdapter(CiTypeCatalogApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Mono<Optional<String>> fetchActiveSchema(UUID organisationId, AssetCategory category) {
        log.debug("[INTEGRATION] Fetching active CI type schema for organisation: {} category: {}", organisationId, category);

        return apiClient.fetchActiveSchema(organisationId.toString(), category.name())
                .map(response -> Optional.of(response.jsonSchema()))
                .onErrorResume(HttpClientResponseException.class, error -> {
                    if (error.getStatus() == HttpStatus.NOT_FOUND) {
                        log.debug("[INTEGRATION] No active CI type schema configured for category {}; skipping validation.", category);
                    } else {
                        log.warn("[INTEGRATION] ci-type-catalog-service returned {} fetching schema for category {}; failing open (no validation applied).",
                                error.getStatus(), category);
                    }
                    return Mono.just(Optional.empty());
                })
                .onErrorResume(Throwable.class, error -> {
                    log.warn("[INTEGRATION] ci-type-catalog-service unreachable fetching schema for category {}; failing open (no validation applied). Reason: {}",
                            category, error.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    @Serdeable
    @Introspected
    record ActiveSchemaApiResponse(UUID id, String category, String jsonSchema, Instant updatedAt) {}
}

/**
 * Declarative Micronaut HTTP Client for the ci-type-catalog Service Domain. Package-private
 * visibility strictly encapsulates this integration detail within the adapter. The 'id' maps to the
 * configuration in application.yml for dynamic resolution.
 */
@Client(id = "ci-type-catalog-service", path = "/ci-type-catalog/v1")
interface CiTypeCatalogApiClient {

    @Get("/active-schema/retrieve")
    Mono<CiTypeCatalogServiceAdapter.ActiveSchemaApiResponse> fetchActiveSchema(
            @Header("X-Tenant-Id") String tenantId,
            @QueryValue("category") String category
    );
}
