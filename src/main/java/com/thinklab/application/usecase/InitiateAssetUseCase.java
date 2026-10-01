package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateAssetRequest;
import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.application.mapper.AssetMapper;
import com.thinklab.domain.exception.DuplicateAssetException;
import com.thinklab.domain.exception.SpecificationValidationException;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.port.CiTypeCatalogPort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.SpecificationValidatorPort;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the business flow for Asset creation (BIAN Behavior Qualifier: {@code initiate}).
 * Serial-number uniqueness is checked explicitly per Organisation before a Sovereign ID is requested.
 *
 * <p><b>Specification validation (ADR-027):</b> once the serial number is confirmed free, the
 * {@code specifications} payload is validated against the tenant's {@code ACTIVE} schema for the
 * requested category, if any is configured — before a Sovereign ID is spent on a request that would
 * otherwise be rejected.
 */
@Singleton
public class InitiateAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateAssetUseCase.class);

    private final HashServicePort hashServicePort;
    private final AssetRepository assetRepository;
    private final CiTypeCatalogPort ciTypeCatalogPort;
    private final SpecificationValidatorPort specificationValidatorPort;

    public InitiateAssetUseCase(HashServicePort hashServicePort, AssetRepository assetRepository,
                                CiTypeCatalogPort ciTypeCatalogPort, SpecificationValidatorPort specificationValidatorPort) {
        this.hashServicePort = hashServicePort;
        this.assetRepository = assetRepository;
        this.ciTypeCatalogPort = ciTypeCatalogPort;
        this.specificationValidatorPort = specificationValidatorPort;
    }

    public Mono<AssetResponse> execute(UUID organisationId, InitiateAssetRequest request, String executor) {
        log.info("[USE CASE] Initiating asset for organisation: {} serial: {}", organisationId, request.serialNumber());

        return assetRepository.existsByOrganisationIdAndSerialNumber(organisationId, request.serialNumber())
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new DuplicateAssetException(String.format(
                                "An Asset already exists for organisation [%s] and serial number [%s].",
                                organisationId, request.serialNumber())));
                    }
                    return validateSpecifications(organisationId, request.category(), request.specifications())
                            .then(Mono.defer(() -> hashServicePort.generateSovereignId("asset-creation")))
                            .map(sovereignId -> AssetMapper.toDomain(request, sovereignId, organisationId, executor))
                            .flatMap(assetRepository::create)
                            .map(AssetMapper::toResponse);
                });
    }

    private Mono<Void> validateSpecifications(UUID organisationId, AssetCategory category, Map<String, String> specifications) {
        return ciTypeCatalogPort.fetchActiveSchema(organisationId, category)
                .flatMap(maybeSchema -> {
                    if (maybeSchema.isEmpty()) {
                        return Mono.empty();
                    }
                    List<String> violations = specificationValidatorPort.validate(maybeSchema.get(), specifications);
                    if (!violations.isEmpty()) {
                        return Mono.error(new SpecificationValidationException(String.format(
                                "Asset specifications violate the configured schema for category [%s].", category),
                                violations));
                    }
                    return Mono.empty();
                });
    }
}
