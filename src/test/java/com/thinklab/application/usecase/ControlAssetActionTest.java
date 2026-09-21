package com.thinklab.application.usecase;

import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlAssetActionTest {

    @Test
    @DisplayName("DEPLOY and MAINTENANCE actions drive the aggregate through its lifecycle")
    void deployAndMaintenanceActions() {
        Asset asset = Asset.createNew(UUID.randomUUID(), UUID.randomUUID(), "srv", AssetCategory.SERVER, "SN-1", Map.of(), "op");
        asset.markReady("op");
        asset.assign(null, UUID.randomUUID(), "op");

        assertEquals(AssetStatus.DEPLOYED, ControlAssetUseCase.Action.DEPLOY.apply(asset, "op").toStatus());
        assertEquals(AssetStatus.MAINTENANCE, ControlAssetUseCase.Action.MAINTENANCE.apply(asset, "op").toStatus());
    }
}
