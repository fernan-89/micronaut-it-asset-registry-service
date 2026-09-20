package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.AssignAssetRequest;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates the binding of an Asset to a holder and/or a location (BIAN Behavior Qualifier:
 * {@code assignment/update}).
 */
@Singleton
public class AssignAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(AssignAssetUseCase.class);

    private final AssetRepository assetRepository;

    public AssignAssetUseCase(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Mono<Void> execute(UUID id, AssignAssetRequest request, String executor) {
        log.info("[USE CASE] Updating assignment for asset ID: {}", id);

        return assetRepository.findById(id)
                .switchIfEmpty(Mono.error(new AssetNotFoundException(id)))
                .flatMap(asset -> {
                    AssetAuditEntry entry = asset.assign(request.assignedToUserId(), request.locationId(), executor);
                    return assetRepository.updateAssignment(id, asset.getAssignedToUserId(), asset.getLocationId(), entry);
                });
    }
}
