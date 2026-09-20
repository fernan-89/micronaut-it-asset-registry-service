package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.application.mapper.AssetMapper;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates single-Asset retrieval (BIAN Behavior Qualifier: {@code retrieve}).
 */
@Singleton
public class RetrieveAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAssetUseCase.class);

    private final AssetRepository assetRepository;

    public RetrieveAssetUseCase(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Mono<AssetResponse> execute(UUID id) {
        log.info("[USE CASE] Retrieving asset ID: {}", id);

        return assetRepository.findById(id)
                .switchIfEmpty(Mono.error(new AssetNotFoundException(id)))
                .map(AssetMapper::toResponse);
    }
}
