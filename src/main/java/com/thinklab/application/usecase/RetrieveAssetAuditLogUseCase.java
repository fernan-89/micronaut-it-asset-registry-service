package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AssetAuditEntryResponse;
import com.thinklab.application.mapper.AssetMapper;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Projects the immutable forensic ledger of an Asset (BIAN Behavior Qualifier: {@code audit-log/retrieve}).
 */
@Singleton
public class RetrieveAssetAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAssetAuditLogUseCase.class);

    private final AssetRepository assetRepository;

    public RetrieveAssetAuditLogUseCase(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Flux<AssetAuditEntryResponse> execute(UUID id) {
        log.info("[USE CASE] Retrieving audit ledger for asset ID: {}", id);

        return assetRepository.findById(id)
                .switchIfEmpty(Mono.error(new AssetNotFoundException(id)))
                .flatMapMany(asset -> Flux.fromIterable(asset.getAuditTrail()))
                .map(AssetMapper::toResponse);
    }
}
