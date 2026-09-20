package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateAssetRequest;
import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.application.mapper.AssetMapper;
import com.thinklab.domain.exception.DuplicateAssetException;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates the business flow for Asset creation (BIAN Behavior Qualifier: {@code initiate}).
 * Serial-number uniqueness is checked explicitly per Organisation before a Sovereign ID is requested.
 */
@Singleton
public class InitiateAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateAssetUseCase.class);

    private final HashServicePort hashServicePort;
    private final AssetRepository assetRepository;

    public InitiateAssetUseCase(HashServicePort hashServicePort, AssetRepository assetRepository) {
        this.hashServicePort = hashServicePort;
        this.assetRepository = assetRepository;
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
                    return hashServicePort.generateSovereignId("asset-creation")
                            .map(sovereignId -> AssetMapper.toDomain(request, sovereignId, organisationId, executor))
                            .flatMap(assetRepository::create)
                            .map(AssetMapper::toResponse);
                });
    }
}
