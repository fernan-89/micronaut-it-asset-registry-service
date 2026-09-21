package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.AssignAssetRequest;
import com.thinklab.application.dto.request.InitiateAssetRequest;
import com.thinklab.application.dto.request.UpdateAssetRequest;
import com.thinklab.application.dto.response.AssetAuditEntryResponse;
import com.thinklab.application.dto.response.AssetResponse;
import com.thinklab.application.usecase.AssignAssetUseCase;
import com.thinklab.application.usecase.ControlAssetUseCase;
import com.thinklab.application.usecase.InitiateAssetUseCase;
import com.thinklab.application.usecase.RetrieveAssetAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveAssetUseCase;
import com.thinklab.application.usecase.RetrieveAssetsUseCase;
import com.thinklab.application.usecase.UpdateAssetUseCase;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.exception.InvalidAssetStatusException;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetControllerTest {

    private static final String EXECUTOR = "ops-admin";

    @Mock private InitiateAssetUseCase initiateAssetUseCase;
    @Mock private RetrieveAssetUseCase retrieveAssetUseCase;
    @Mock private RetrieveAssetsUseCase retrieveAssetsUseCase;
    @Mock private UpdateAssetUseCase updateAssetUseCase;
    @Mock private AssignAssetUseCase assignAssetUseCase;
    @Mock private ControlAssetUseCase controlAssetUseCase;
    @Mock private RetrieveAssetAuditLogUseCase retrieveAssetAuditLogUseCase;

    @InjectMocks
    private AssetController controller;

    private UUID organisationId;
    private UUID assetId;
    private AssetResponse sample;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        assetId = UUID.randomUUID();
        sample = new AssetResponse(assetId, organisationId, "Core Switch", "NETWORK_DEVICE", "SN-77",
                Map.of("ports", "48"), "PROVISIONED", null, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("initiate should return 201 Created with the new asset")
    void initiate() {
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", null);
        when(initiateAssetUseCase.execute(eq(organisationId), eq(request), eq(EXECUTOR))).thenReturn(Mono.just(sample));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatus());
                    assertEquals(assetId, response.body().id());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate should reject a malformed tenant header before reaching the use case")
    void initiateMalformedTenant() {
        InitiateAssetRequest request = new InitiateAssetRequest("n", AssetCategory.LAPTOP, "s", null);

        assertThrows(IllegalArgumentException.class, () -> controller.initiate("not-a-uuid", EXECUTOR, request));
    }

    @Test
    @DisplayName("initiate should propagate a duplicate/domain error")
    void initiatePropagatesError() {
        InitiateAssetRequest request = new InitiateAssetRequest("n", AssetCategory.LAPTOP, "s", null);
        when(initiateAssetUseCase.execute(any(), any(), any())).thenReturn(Mono.error(new AssetNotFoundException("boom")));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .expectError(AssetNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("retrieveById should return 200 OK")
    void retrieveById() {
        when(retrieveAssetUseCase.execute(assetId)).thenReturn(Mono.just(sample));

        StepVerifier.create(controller.retrieveById(assetId))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatus());
                    assertEquals("Core Switch", response.body().name());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("retrieveById should surface not-found from the use case")
    void retrieveByIdNotFound() {
        when(retrieveAssetUseCase.execute(assetId)).thenReturn(Mono.error(new AssetNotFoundException(assetId)));

        StepVerifier.create(controller.retrieveById(assetId)).expectError(AssetNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieveAll should scope by tenant and forward status/category filters")
    void retrieveAll() {
        when(retrieveAssetsUseCase.execute(organisationId, AssetStatus.READY, AssetCategory.SERVER)).thenReturn(Flux.just(sample));
        when(retrieveAssetsUseCase.execute(organisationId, null, null)).thenReturn(Flux.just(sample, sample));

        StepVerifier.create(controller.retrieveAll(organisationId.toString(), AssetStatus.READY, AssetCategory.SERVER))
                .expectNext(List.of(sample)).verifyComplete();
        StepVerifier.create(controller.retrieveAll(organisationId.toString(), null, null))
                .assertNext(list -> assertEquals(2, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("update should return 204 No Content and pass the executor to the use case")
    void update() {
        UpdateAssetRequest request = new UpdateAssetRequest("Core Switch v2", Map.of());
        when(updateAssetUseCase.execute(assetId, request, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.update(assetId, EXECUTOR, request))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus()))
                .verifyComplete();
        verify(updateAssetUseCase).execute(assetId, request, EXECUTOR);
    }

    @Test
    @DisplayName("updateAssignment should return 204 No Content")
    void updateAssignment() {
        AssignAssetRequest request = new AssignAssetRequest(UUID.randomUUID(), UUID.randomUUID());
        when(assignAssetUseCase.execute(assetId, request, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.updateAssignment(assetId, EXECUTOR, request))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus()))
                .verifyComplete();
    }

    @Test
    @DisplayName("each control endpoint should dispatch its action and return 204")
    void controlEndpoints() {
        when(controlAssetUseCase.execute(eq(assetId), any(ControlAssetUseCase.Action.class), eq(EXECUTOR))).thenReturn(Mono.empty());

        StepVerifier.create(controller.controlReady(assetId, EXECUTOR))
                .assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlDeploy(assetId, EXECUTOR))
                .assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlMaintenance(assetId, EXECUTOR))
                .assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlDecommission(assetId, EXECUTOR))
                .assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();

        verify(controlAssetUseCase).execute(assetId, ControlAssetUseCase.Action.READY, EXECUTOR);
        verify(controlAssetUseCase).execute(assetId, ControlAssetUseCase.Action.DEPLOY, EXECUTOR);
        verify(controlAssetUseCase).execute(assetId, ControlAssetUseCase.Action.MAINTENANCE, EXECUTOR);
        verify(controlAssetUseCase).execute(assetId, ControlAssetUseCase.Action.DECOMMISSION, EXECUTOR);
    }

    @Test
    @DisplayName("a control endpoint should surface an illegal transition (409) from the use case")
    void controlIllegalTransition() {
        when(controlAssetUseCase.execute(assetId, ControlAssetUseCase.Action.DEPLOY, EXECUTOR))
                .thenReturn(Mono.error(new InvalidAssetStatusException("illegal")));

        StepVerifier.create(controller.controlDeploy(assetId, EXECUTOR))
                .expectError(InvalidAssetStatusException.class)
                .verify();
    }

    @Test
    @DisplayName("retrieveAuditLog should stream the ledger entries")
    void retrieveAuditLog() {
        AssetAuditEntryResponse entry = new AssetAuditEntryResponse(Instant.now(), "INITIATED", EXECUTOR, null, "PROVISIONED", "d");
        when(retrieveAssetAuditLogUseCase.execute(assetId)).thenReturn(Flux.just(entry));

        StepVerifier.create(controller.retrieveAuditLog(assetId))
                .assertNext(e -> {
                    assertNotNull(e);
                    assertEquals("INITIATED", e.action());
                })
                .verifyComplete();
    }
}
