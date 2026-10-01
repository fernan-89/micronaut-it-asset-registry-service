package com.thinklab.domain.port;

import com.thinklab.domain.model.Asset.AssetCategory;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound Port to {@code ci-type-catalog-service}: the single lookup this service needs, "does this
 * tenant+category have an ACTIVE schema configured?" (ADR-027).
 *
 * <p>Deliberately <b>fails open</b> on every failure mode - "not configured" (the catalog's own 404)
 * and "catalog unreachable/erroring" alike both resolve to {@link Optional#empty()}, never an error
 * signal. This is a brand-new, optional, bolt-on dependency for an already-public, core-path service;
 * see this interface's implementation for the full rationale and precedent.
 */
public interface CiTypeCatalogPort {

    Mono<Optional<String>> fetchActiveSchema(UUID organisationId, AssetCategory category);
}
