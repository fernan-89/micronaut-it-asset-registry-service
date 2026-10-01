package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.AssignAssetRequest;
import com.thinklab.application.dto.request.InitiateAssetRequest;
import com.thinklab.application.dto.request.UpdateAssetRequest;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.exception.DuplicateAssetException;
import com.thinklab.domain.exception.InvalidAssetStatusException;
import com.thinklab.domain.exception.SpecificationValidationException;
import com.thinklab.domain.model.Asset;
import com.thinklab.domain.model.Asset.AssetAuditEntry;
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import com.thinklab.domain.port.CiTypeCatalogPort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.SpecificationValidatorPort;
import com.thinklab.domain.repository.AssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetUseCaseTest {

    private static final String EXECUTOR = "ops-admin";
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Mock private AssetRepository assetRepository;
    @Mock private HashServicePort hashServicePort;
    @Mock private CiTypeCatalogPort ciTypeCatalogPort;
    @Mock private SpecificationValidatorPort specificationValidatorPort;

    private UUID organisationId;
    private UUID assetId;
    private UUID locationId;
    private Asset asset;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        assetId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        asset = Asset.createNew(assetId, organisationId, "Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77",
                Map.of("ports", "48"), EXECUTOR);
    }

    // ---------------------------------------------------------------- initiate

    @Test
    @DisplayName("Initiate: should obtain a sovereign ID, persist the aggregate and return the response")
    void initiateSuccess() {
        UUID sovereignId = UUID.randomUUID();
        InitiateAssetUseCase useCase = new InitiateAssetUseCase(hashServicePort, assetRepository, ciTypeCatalogPort, specificationValidatorPort);
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", Map.of("ports", "48"));

        when(assetRepository.existsByOrganisationIdAndSerialNumber(organisationId, "SN-77")).thenReturn(Mono.just(false));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.empty()));
        when(hashServicePort.generateSovereignId("asset-creation")).thenReturn(Mono.just(sovereignId));
        when(assetRepository.create(any(Asset.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .assertNext(response -> {
                    assertEquals(sovereignId, response.id());
                    assertEquals(organisationId, response.organisationId());
                    assertEquals("PROVISIONED", response.status());
                    assertEquals("NETWORK_DEVICE", response.category());
                    assertEquals("SN-77", response.serialNumber());
                })
                .verifyComplete();

        verifyNoInteractions(specificationValidatorPort);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).create(captor.capture());
        assertEquals(1, captor.getValue().getAuditTrail().size());
        assertEquals(EXECUTOR, captor.getValue().getAuditTrail().get(0).executor());
    }

    @Test
    @DisplayName("Initiate: should reject a duplicate serial number (409) before calling the hash service")
    void initiateRejectsDuplicateSerial() {
        InitiateAssetUseCase useCase = new InitiateAssetUseCase(hashServicePort, assetRepository, ciTypeCatalogPort, specificationValidatorPort);
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", null);

        when(assetRepository.existsByOrganisationIdAndSerialNumber(organisationId, "SN-77")).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectErrorSatisfies(error -> {
                    assertEquals(DuplicateAssetException.class, error.getClass());
                    assertEquals("ERR-AST-00409", ((DuplicateAssetException) error).getErrorCode());
                })
                .verify();

        verifyNoInteractions(hashServicePort);
        verifyNoInteractions(ciTypeCatalogPort);
        verify(assetRepository, never()).create(any());
    }

    @Test
    @DisplayName("Initiate: should propagate a hash-service failure without persisting anything")
    void initiatePropagatesHashFailure() {
        InitiateAssetUseCase useCase = new InitiateAssetUseCase(hashServicePort, assetRepository, ciTypeCatalogPort, specificationValidatorPort);
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", null);

        when(assetRepository.existsByOrganisationIdAndSerialNumber(any(), anyString())).thenReturn(Mono.just(false));
        when(ciTypeCatalogPort.fetchActiveSchema(any(), any())).thenReturn(Mono.just(Optional.empty()));
        when(hashServicePort.generateSovereignId(anyString())).thenReturn(Mono.error(new IllegalStateException("hash down")));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectErrorMessage("hash down")
                .verify();

        verify(assetRepository, never()).create(any());
    }

    @Test
    @DisplayName("Initiate: should succeed unvalidated when no ACTIVE schema is configured for the category (ADR-027)")
    void initiateSucceedsWhenNoSchemaConfigured() {
        UUID sovereignId = UUID.randomUUID();
        InitiateAssetUseCase useCase = new InitiateAssetUseCase(hashServicePort, assetRepository, ciTypeCatalogPort, specificationValidatorPort);
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", Map.of("ports", "48"));

        when(assetRepository.existsByOrganisationIdAndSerialNumber(organisationId, "SN-77")).thenReturn(Mono.just(false));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.empty()));
        when(hashServicePort.generateSovereignId("asset-creation")).thenReturn(Mono.just(sovereignId));
        when(assetRepository.create(any(Asset.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectNextCount(1)
                .verifyComplete();

        verifyNoInteractions(specificationValidatorPort);
    }

    @Test
    @DisplayName("Initiate: should succeed when specifications conform to the configured ACTIVE schema (ADR-027)")
    void initiateSucceedsWhenSpecificationsConform() {
        UUID sovereignId = UUID.randomUUID();
        InitiateAssetUseCase useCase = new InitiateAssetUseCase(hashServicePort, assetRepository, ciTypeCatalogPort, specificationValidatorPort);
        Map<String, String> specifications = Map.of("ports", "48");
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", specifications);

        when(assetRepository.existsByOrganisationIdAndSerialNumber(organisationId, "SN-77")).thenReturn(Mono.just(false));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.of(SCHEMA)));
        when(specificationValidatorPort.validate(SCHEMA, specifications)).thenReturn(List.of());
        when(hashServicePort.generateSovereignId("asset-creation")).thenReturn(Mono.just(sovereignId));
        when(assetRepository.create(any(Asset.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Initiate: should reject specifications that violate the configured ACTIVE schema (422) and never persist")
    void initiateRejectsNonConformingSpecifications() {
        InitiateAssetUseCase useCase = new InitiateAssetUseCase(hashServicePort, assetRepository, ciTypeCatalogPort, specificationValidatorPort);
        Map<String, String> specifications = Map.of("ports", "not-a-number");
        InitiateAssetRequest request = new InitiateAssetRequest("Core Switch", AssetCategory.NETWORK_DEVICE, "SN-77", specifications);

        when(assetRepository.existsByOrganisationIdAndSerialNumber(organisationId, "SN-77")).thenReturn(Mono.just(false));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.of(SCHEMA)));
        when(specificationValidatorPort.validate(SCHEMA, specifications)).thenReturn(List.of("$.ports: must be a number"));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectErrorSatisfies(error -> {
                    assertEquals(SpecificationValidationException.class, error.getClass());
                    SpecificationValidationException ex = (SpecificationValidationException) error;
                    assertEquals("ERR-AST-00422", ex.getErrorCode());
                    assertEquals(List.of("$.ports: must be a number"), ex.getViolations());
                })
                .verify();

        verifyNoInteractions(hashServicePort);
        verify(assetRepository, never()).create(any());
    }

    // ---------------------------------------------------------------- retrieve

    @Test
    @DisplayName("Retrieve: should project the aggregate to a response")
    void retrieveSuccess() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));

        StepVerifier.create(new RetrieveAssetUseCase(assetRepository).execute(assetId))
                .assertNext(response -> {
                    assertEquals(assetId, response.id());
                    assertEquals("Core Switch", response.name());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Retrieve: should fail with AssetNotFoundException (404) when absent")
    void retrieveNotFound() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.empty());

        StepVerifier.create(new RetrieveAssetUseCase(assetRepository).execute(assetId))
                .expectErrorSatisfies(error -> {
                    assertEquals(AssetNotFoundException.class, error.getClass());
                    assertEquals("ERR-AST-00404", ((AssetNotFoundException) error).getErrorCode());
                })
                .verify();
    }

    @Test
    @DisplayName("Retrieve collection: should forward tenant, status and category filters")
    void retrieveCollection() {
        when(assetRepository.findAllByOrganisationId(organisationId, AssetStatus.READY, AssetCategory.LAPTOP))
                .thenReturn(Flux.just(asset));
        when(assetRepository.findAllByOrganisationId(organisationId, null, null)).thenReturn(Flux.empty());
        RetrieveAssetsUseCase useCase = new RetrieveAssetsUseCase(assetRepository);

        StepVerifier.create(useCase.execute(organisationId, AssetStatus.READY, AssetCategory.LAPTOP))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(useCase.execute(organisationId, null, null))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- update / assign

    @Test
    @DisplayName("Update: should mutate the aggregate and persist the change together with its audit entry")
    void updateSuccess() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.empty()));
        when(assetRepository.updateBasicInfo(eq(assetId), eq("Core Switch v2"), eq(Map.of("ports", "96")), any(AssetAuditEntry.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(new UpdateAssetUseCase(assetRepository, ciTypeCatalogPort, specificationValidatorPort)
                        .execute(assetId, new UpdateAssetRequest("Core Switch v2", Map.of("ports", "96")), "tech-9"))
                .verifyComplete();

        verifyNoInteractions(specificationValidatorPort);

        ArgumentCaptor<AssetAuditEntry> captor = ArgumentCaptor.forClass(AssetAuditEntry.class);
        verify(assetRepository).updateBasicInfo(eq(assetId), eq("Core Switch v2"), any(), captor.capture());
        assertEquals("UPDATED", captor.getValue().action());
        assertEquals("tech-9", captor.getValue().executor());
    }

    @Test
    @DisplayName("Update: should fail with 404 when the asset does not exist and never write")
    void updateNotFound() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.empty());

        StepVerifier.create(new UpdateAssetUseCase(assetRepository, ciTypeCatalogPort, specificationValidatorPort)
                        .execute(assetId, new UpdateAssetRequest("n", null), EXECUTOR))
                .expectError(AssetNotFoundException.class)
                .verify();

        verifyNoInteractions(ciTypeCatalogPort);
        verify(assetRepository, never()).updateBasicInfo(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Update: should reject the mutation of a DECOMMISSIONED asset (409) and never write")
    void updateRejectedWhenDecommissioned() {
        asset.decommission(EXECUTOR);
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.empty()));

        StepVerifier.create(new UpdateAssetUseCase(assetRepository, ciTypeCatalogPort, specificationValidatorPort)
                        .execute(assetId, new UpdateAssetRequest("n", null), EXECUTOR))
                .expectError(InvalidAssetStatusException.class)
                .verify();

        verify(assetRepository, never()).updateBasicInfo(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Update: should succeed when specifications conform to the configured ACTIVE schema (ADR-027)")
    void updateSucceedsWhenSpecificationsConform() {
        Map<String, String> specifications = Map.of("ports", "96");
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.of(SCHEMA)));
        when(specificationValidatorPort.validate(SCHEMA, specifications)).thenReturn(List.of());
        when(assetRepository.updateBasicInfo(eq(assetId), eq("Core Switch v2"), eq(specifications), any(AssetAuditEntry.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(new UpdateAssetUseCase(assetRepository, ciTypeCatalogPort, specificationValidatorPort)
                        .execute(assetId, new UpdateAssetRequest("Core Switch v2", specifications), EXECUTOR))
                .verifyComplete();

        verify(assetRepository).updateBasicInfo(eq(assetId), eq("Core Switch v2"), eq(specifications), any());
    }

    @Test
    @DisplayName("Update: should reject specifications that violate the configured ACTIVE schema (422) and never write")
    void updateRejectsNonConformingSpecifications() {
        Map<String, String> specifications = Map.of("ports", "not-a-number");
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(ciTypeCatalogPort.fetchActiveSchema(organisationId, AssetCategory.NETWORK_DEVICE)).thenReturn(Mono.just(Optional.of(SCHEMA)));
        when(specificationValidatorPort.validate(SCHEMA, specifications)).thenReturn(List.of("$.ports: must be a number"));

        StepVerifier.create(new UpdateAssetUseCase(assetRepository, ciTypeCatalogPort, specificationValidatorPort)
                        .execute(assetId, new UpdateAssetRequest("Core Switch v2", specifications), EXECUTOR))
                .expectErrorSatisfies(error -> {
                    assertEquals(SpecificationValidationException.class, error.getClass());
                    assertEquals("ERR-AST-00422", ((SpecificationValidationException) error).getErrorCode());
                })
                .verify();

        verify(assetRepository, never()).updateBasicInfo(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Assign: should persist the assignment with an ASSIGNED audit entry")
    void assignSuccess() {
        UUID holder = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(assetRepository.updateAssignment(eq(assetId), eq(holder), eq(locationId), any(AssetAuditEntry.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(new AssignAssetUseCase(assetRepository)
                        .execute(assetId, new AssignAssetRequest(holder, locationId), EXECUTOR))
                .verifyComplete();

        ArgumentCaptor<AssetAuditEntry> captor = ArgumentCaptor.forClass(AssetAuditEntry.class);
        verify(assetRepository).updateAssignment(eq(assetId), eq(holder), eq(locationId), captor.capture());
        assertEquals("ASSIGNED", captor.getValue().action());
    }

    @Test
    @DisplayName("Assign: should fail with 404 when the asset does not exist")
    void assignNotFound() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.empty());

        StepVerifier.create(new AssignAssetUseCase(assetRepository)
                        .execute(assetId, new AssignAssetRequest(null, null), EXECUTOR))
                .expectError(AssetNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Assign: should reject clearing the location of a DEPLOYED asset (422)")
    void assignRejectedWhenDeployedWithoutLocation() {
        asset.assign(null, locationId, EXECUTOR);
        asset.markReady(EXECUTOR);
        asset.deploy(EXECUTOR);
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));

        StepVerifier.create(new AssignAssetUseCase(assetRepository)
                        .execute(assetId, new AssignAssetRequest(null, null), EXECUTOR))
                .expectError(InvalidAssetStatusException.class)
                .verify();

        verify(assetRepository, never()).updateAssignment(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- control

    @Test
    @DisplayName("Control: READY on a PROVISIONED asset persists the new status with its audit entry")
    void controlReady() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(assetRepository.updateStatus(eq(assetId), eq(AssetStatus.READY), any(AssetAuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(new ControlAssetUseCase(assetRepository).execute(assetId, ControlAssetUseCase.Action.READY, EXECUTOR))
                .verifyComplete();

        ArgumentCaptor<AssetAuditEntry> captor = ArgumentCaptor.forClass(AssetAuditEntry.class);
        verify(assetRepository).updateStatus(eq(assetId), eq(AssetStatus.READY), captor.capture());
        assertEquals(AssetStatus.PROVISIONED, captor.getValue().fromStatus());
        assertEquals(AssetStatus.READY, captor.getValue().toStatus());
    }

    @Test
    @DisplayName("Control: the four actions map to their target statuses")
    void controlActionsMapToStatuses() {
        assertEquals(AssetStatus.READY, ControlAssetUseCase.Action.READY.targetStatus());
        assertEquals(AssetStatus.DEPLOYED, ControlAssetUseCase.Action.DEPLOY.targetStatus());
        assertEquals(AssetStatus.MAINTENANCE, ControlAssetUseCase.Action.MAINTENANCE.targetStatus());
        assertEquals(AssetStatus.DECOMMISSIONED, ControlAssetUseCase.Action.DECOMMISSION.targetStatus());
    }

    @Test
    @DisplayName("Control: DEPLOY on a PROVISIONED asset is an illegal transition (422) and never writes")
    void controlIllegalTransition() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));

        StepVerifier.create(new ControlAssetUseCase(assetRepository).execute(assetId, ControlAssetUseCase.Action.DEPLOY, EXECUTOR))
                .expectErrorSatisfies(error -> {
                    assertEquals(InvalidAssetStatusException.class, error.getClass());
                    assertEquals("ERR-AST-00409", ((InvalidAssetStatusException) error).getErrorCode());
                })
                .verify();

        verify(assetRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("Control: should fail with 404 when the asset does not exist")
    void controlNotFound() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.empty());

        StepVerifier.create(new ControlAssetUseCase(assetRepository).execute(assetId, ControlAssetUseCase.Action.DECOMMISSION, EXECUTOR))
                .expectError(AssetNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Control: DECOMMISSION drives the terminal transition")
    void controlDecommission() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));
        when(assetRepository.updateStatus(eq(assetId), eq(AssetStatus.DECOMMISSIONED), any())).thenReturn(Mono.empty());

        StepVerifier.create(new ControlAssetUseCase(assetRepository).execute(assetId, ControlAssetUseCase.Action.DECOMMISSION, EXECUTOR))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- audit log

    @Test
    @DisplayName("Audit log: should project every ledger entry in order")
    void auditLogSuccess() {
        asset.markReady(EXECUTOR);
        when(assetRepository.findById(assetId)).thenReturn(Mono.just(asset));

        StepVerifier.create(new RetrieveAssetAuditLogUseCase(assetRepository).execute(assetId))
                .assertNext(entry -> assertEquals("INITIATED", entry.action()))
                .assertNext(entry -> {
                    assertEquals("STATUS_CHANGED", entry.action());
                    assertEquals("PROVISIONED", entry.fromStatus());
                    assertEquals("READY", entry.toStatus());
                    assertNotNull(entry.occurredAt());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audit log: should fail with 404 when the asset does not exist")
    void auditLogNotFound() {
        when(assetRepository.findById(assetId)).thenReturn(Mono.empty());

        StepVerifier.create(new RetrieveAssetAuditLogUseCase(assetRepository).execute(assetId))
                .expectError(AssetNotFoundException.class)
                .verify();
    }
}
