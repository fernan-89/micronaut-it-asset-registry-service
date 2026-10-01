package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateAssetRequest;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.exception.SpecificationValidationException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.port.CiTypeCatalogPort;
import com.thinklab.domain.port.SpecificationValidatorPort;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates updates to the descriptive information of an Asset (BIAN Behavior Qualifier:
 * {@code update}). The domain model validates the mutation and produces the audit entry that is
 * persisted atomically with the granular update.
 *
 * <p><b>Specification validation (ADR-027):</b> the new {@code specifications} payload is validated
 * against the tenant's {@code ACTIVE} schema for the asset's category, if any is configured, before the
 * domain mutation is applied — never a partial write on a rejected update.
 */
@Singleton
public class UpdateAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateAssetUseCase.class);

    private final AssetRepository assetRepository;
    private final CiTypeCatalogPort ciTypeCatalogPort;
    private final SpecificationValidatorPort specificationValidatorPort;

    public UpdateAssetUseCase(AssetRepository assetRepository, CiTypeCatalogPort ciTypeCatalogPort,
                              SpecificationValidatorPort specificationValidatorPort) {
        this.assetRepository = assetRepository;
        this.ciTypeCatalogPort = ciTypeCatalogPort;
        this.specificationValidatorPort = specificationValidatorPort;
    }

    public Mono<Void> execute(UUID id, UpdateAssetRequest request, String executor) {
        log.info("[USE CASE] Updating basic info for asset ID: {}", id);

        return assetRepository.findById(id)
                .switchIfEmpty(Mono.error(new AssetNotFoundException(id)))
                .flatMap(asset -> validateSpecifications(asset, request)
                        .then(Mono.defer(() -> {
                            AssetAuditEntry entry = asset.updateInfo(request.name(), request.specifications(), executor);
                            return assetRepository.updateBasicInfo(id, asset.getName(), asset.getSpecifications(), entry);
                        })));
    }

    private Mono<Void> validateSpecifications(Asset asset, UpdateAssetRequest request) {
        return ciTypeCatalogPort.fetchActiveSchema(asset.getOrganisationId(), asset.getCategory())
                .flatMap(maybeSchema -> {
                    if (maybeSchema.isEmpty()) {
                        return Mono.empty();
                    }
                    List<String> violations = specificationValidatorPort.validate(maybeSchema.get(), request.specifications());
                    if (!violations.isEmpty()) {
                        return Mono.error(new SpecificationValidationException(String.format(
                                "Asset specifications violate the configured schema for category [%s].", asset.getCategory()),
                                violations));
                    }
                    return Mono.empty();
                });
    }
}
