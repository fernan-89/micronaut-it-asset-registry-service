package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.application.mapper.AssetMapper;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Orchestrates the tenant-scoped listing of Assets (BIAN Behavior Qualifier: {@code retrieve} —
 * collection). Every query is strictly bound to the {@code organisationId} from {@code X-Tenant-Id}.
 */
@Singleton
public class RetrieveAssetsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAssetsUseCase.class);

    private final AssetRepository assetRepository;

    public RetrieveAssetsUseCase(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Flux<AssetResponse> execute(UUID organisationId, AssetStatus status, AssetCategory category) {
        log.info("[USE CASE] Retrieving assets for organisation: {} status: {} category: {}", organisationId, status, category);

        return assetRepository.findAllByOrganisationId(organisationId, status, category)
                .map(AssetMapper::toResponse);
    }
}
