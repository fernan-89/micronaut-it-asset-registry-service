package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.usecase.ControlAssetUseCase;
import com.thinklab.domain.exception.InvalidAssetStatusException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.domain.repository.AssetRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The asset transition races against a real MongoDB (platform race audit, ACTION-PLAN 0.5): an asset read by two requests at once must have exactly one
 * of them win, and the stored status must agree with the trail. The same use case answers the maintenance events of the work orders, so a lost race
 * there would leave an asset in the wrong status.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransitionRaceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_asset_registry_race_it";
    private static final String EXECUTOR = "it-operator";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject AssetRepository assets;
    @Inject ControlAssetUseCase control;

    private Asset provisioned() {
        Asset asset = Asset.createNew(UUID.randomUUID(), UUID.randomUUID(), "ThinkPad P1", AssetCategory.LAPTOP, "SN-" + UUID.randomUUID(), Map.of("cpu", "i9"), EXECUTOR);
        assets.create(asset).block();
        return asset;
    }

    private List<String> run(UUID id, ControlAssetUseCase.Action... actions) {
        return Flux.fromArray(actions)
                .flatMap(action -> control.execute(id, action, EXECUTOR).thenReturn("OK:" + action.name())
                        .onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), actions.length)
                .collectList().block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("ten 'ready' of one provisioned asset at once: exactly one wins, the others are refused with a conflict")
    void tenReadiesAtOnce() {
        Asset asset = provisioned();
        ControlAssetUseCase.Action[] ten = new ControlAssetUseCase.Action[10];
        Arrays.fill(ten, ControlAssetUseCase.Action.READY);

        List<String> results = run(asset.getId(), ten);

        assertEquals(1, results.stream().filter(r -> r.startsWith("OK")).count(), results.toString());
        assertEquals(9, results.stream().filter(InvalidAssetStatusException.class.getSimpleName()::equals).count(), results.toString());
        Asset stored = assets.findById(asset.getId()).block();
        assertEquals(AssetStatus.READY, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size(), "one entry for creating and one for the single transition that won");
    }

    @Test
    @DisplayName("a 'ready' and a decommission at once: one wins, and the stored status is the winner's")
    void readyAgainstDecommission() {
        Asset asset = provisioned();

        List<String> results = run(asset.getId(), ControlAssetUseCase.Action.READY, ControlAssetUseCase.Action.DECOMMISSION);

        List<String> winners = results.stream().filter(r -> r.startsWith("OK")).toList();
        assertEquals(1, winners.size(), results.toString());
        Asset stored = assets.findById(asset.getId()).block();
        assertEquals(winners.get(0).equals("OK:READY") ? AssetStatus.READY : AssetStatus.DECOMMISSIONED, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size());
    }
}
