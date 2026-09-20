package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateAssetRequest;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates updates to the descriptive information of an Asset (BIAN Behavior Qualifier:
 * {@code update}). The domain model validates the mutation and produces the audit entry that is
 * persisted atomically with the granular update.
 */
@Singleton
public class UpdateAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateAssetUseCase.class);

    private final AssetRepository assetRepository;

    public UpdateAssetUseCase(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Mono<Void> execute(UUID id, UpdateAssetRequest request, String executor) {
        log.info("[USE CASE] Updating basic info for asset ID: {}", id);

        return assetRepository.findById(id)
                .switchIfEmpty(Mono.error(new AssetNotFoundException(id)))
                .flatMap(asset -> {
                    AssetAuditEntry entry = asset.updateInfo(request.name(), request.specifications(), executor);
                    return assetRepository.updateBasicInfo(id, asset.getName(), asset.getSpecifications(), entry);
                });
    }
}
