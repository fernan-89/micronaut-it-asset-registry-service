package com.thinklab.application.usecase;

import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.domain.repository.AssetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case governing the Asset lifecycle (BIAN Behavior Qualifier: {@code control}).
 *
 * <p><b>State Machine Enforcement:</b> loads the aggregate first, delegates the transition to the
 * domain model (which throws {@link com.thinklab.domain.exception.InvalidAssetStatusException} — HTTP
 * 422 — on an illegal move) and only then issues the granular persistence update together with the
 * audit entry — never a blind partial write.
 */
@Singleton
public class ControlAssetUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlAssetUseCase.class);

    private final AssetRepository assetRepository;

    public ControlAssetUseCase(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Mono<Void> execute(UUID id, Action action, String executor) {
        log.info("[USE CASE] Controlling asset lifecycle: {} for ID: {}", action, id);

        return assetRepository.findById(id)
                .switchIfEmpty(Mono.error(new AssetNotFoundException(id)))
                .flatMap(asset -> {
                    AssetAuditEntry entry = action.apply(asset, executor);
                    return assetRepository.updateStatus(id, action.targetStatus(), entry);
                });
    }

    public enum Action {
        READY(AssetStatus.READY) {
            @Override AssetAuditEntry apply(Asset asset, String executor) { return asset.markReady(executor); }
        },
        DEPLOY(AssetStatus.DEPLOYED) {
            @Override AssetAuditEntry apply(Asset asset, String executor) { return asset.deploy(executor); }
        },
        MAINTENANCE(AssetStatus.MAINTENANCE) {
            @Override AssetAuditEntry apply(Asset asset, String executor) { return asset.startMaintenance(executor); }
        },
        DECOMMISSION(AssetStatus.DECOMMISSIONED) {
            @Override AssetAuditEntry apply(Asset asset, String executor) { return asset.decommission(executor); }
        };

        private final AssetStatus targetStatus;

        Action(AssetStatus targetStatus) {
            this.targetStatus = targetStatus;
        }

        public AssetStatus targetStatus() {
            return targetStatus;
        }

        abstract AssetAuditEntry apply(Asset asset, String executor);
    }
}
