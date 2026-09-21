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
import com.thinklab.domain.model.Asset.AssetCategory;
import com.thinklab.domain.model.Asset.AssetStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-asset-registry} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.Asset} is the
 * Control Record. Every route follows {@code /it-asset-registry/v1/{control-record-id}/{behavior-qualifier}}.
 * There is no {@code DELETE}: {@code control/decommission} is a terminal, soft status transition.
 *
 * <p><b>Header-Sourced Forensics (ADR-013):</b> {@code X-Tenant-Id} (organisationId) is mandatory on
 * {@code initiate} and collection {@code retrieve}; {@code X-Executor} is mandatory on every mutation
 * and is recorded in the Asset's immutable audit ledger.
 */
@Controller("/it-asset-registry/v1")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiateAssetUseCase initiateAssetUseCase;
    private final RetrieveAssetUseCase retrieveAssetUseCase;
    private final RetrieveAssetsUseCase retrieveAssetsUseCase;
    private final UpdateAssetUseCase updateAssetUseCase;
    private final AssignAssetUseCase assignAssetUseCase;
    private final ControlAssetUseCase controlAssetUseCase;
    private final RetrieveAssetAuditLogUseCase retrieveAssetAuditLogUseCase;

    public AssetController(
            InitiateAssetUseCase initiateAssetUseCase,
            RetrieveAssetUseCase retrieveAssetUseCase,
            RetrieveAssetsUseCase retrieveAssetsUseCase,
            UpdateAssetUseCase updateAssetUseCase,
            AssignAssetUseCase assignAssetUseCase,
            ControlAssetUseCase controlAssetUseCase,
            RetrieveAssetAuditLogUseCase retrieveAssetAuditLogUseCase
    ) {
        this.initiateAssetUseCase = initiateAssetUseCase;
        this.retrieveAssetUseCase = retrieveAssetUseCase;
        this.retrieveAssetsUseCase = retrieveAssetsUseCase;
        this.updateAssetUseCase = updateAssetUseCase;
        this.assignAssetUseCase = assignAssetUseCase;
        this.controlAssetUseCase = controlAssetUseCase;
        this.retrieveAssetAuditLogUseCase = retrieveAssetAuditLogUseCase;
    }

    /** Behavior Qualifier: {@code initiate}. Registers a new Asset Control Record. */
    @Post("/initiate")
    public Mono<HttpResponse<AssetResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid InitiateAssetRequest request
    ) {
        log.info("[ACTION: INITIATE_ASSET] [EXECUTOR: {}] Received request to register asset for organisation: {} serial: {}",
                executor, tenantId, request.serialNumber());

        return initiateAssetUseCase.execute(UUID.fromString(tenantId), request, executor)
                .map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches a single Asset by UUID. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<AssetResponse>> retrieveById(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_ASSET] Received request to get asset by ID: {}", id);

        return retrieveAssetUseCase.execute(id).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). Lists Assets scoped to a tenant. */
    @Get("/retrieve")
    public Mono<List<AssetResponse>> retrieveAll(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @QueryValue @Nullable AssetStatus status,
            @QueryValue @Nullable AssetCategory category
    ) {
        log.info("[ACTION: RETRIEVE_ASSETS] Received request to list assets for organisation: {} status: {} category: {}",
                tenantId, status, category);

        return Mono.defer(() -> retrieveAssetsUseCase.execute(UUID.fromString(tenantId), status, category).collectList());
    }

    /** Behavior Qualifier: {@code update}. Updates the descriptive information of an Asset. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid UpdateAssetRequest request
    ) {
        log.info("[ACTION: UPDATE_ASSET] [EXECUTOR: {}] Received request to update asset ID: {}", executor, id);

        return updateAssetUseCase.execute(id, request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code assignment/update}. Binds the Asset to a holder and/or location. */
    @Put("/{id}/assignment/update")
    public Mono<HttpResponse<Void>> updateAssignment(
            @PathVariable UUID id,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid AssignAssetRequest request
    ) {
        log.info("[ACTION: ASSIGN_ASSET] [EXECUTOR: {}] Received request to update assignment for asset ID: {}", executor, id);

        return assignAssetUseCase.execute(id, request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/ready}. Marks the Asset as ready (in stock). */
    @Put("/{id}/control/ready")
    public Mono<HttpResponse<Void>> controlReady(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlAssetUseCase.Action.READY, executor);
    }

    /** Behavior Qualifier: {@code control/deploy}. Requires a location to be assigned. */
    @Put("/{id}/control/deploy")
    public Mono<HttpResponse<Void>> controlDeploy(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlAssetUseCase.Action.DEPLOY, executor);
    }

    /** Behavior Qualifier: {@code control/maintenance}. Pulls a deployed Asset into maintenance. */
    @Put("/{id}/control/maintenance")
    public Mono<HttpResponse<Void>> controlMaintenance(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlAssetUseCase.Action.MAINTENANCE, executor);
    }

    /** Behavior Qualifier: {@code control/decommission}. Terminal, soft — no physical DELETE exists. */
    @Put("/{id}/control/decommission")
    public Mono<HttpResponse<Void>> controlDecommission(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlAssetUseCase.Action.DECOMMISSION, executor);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the Asset. */
    @Get("/{id}/audit-log/retrieve")
    public Flux<AssetAuditEntryResponse> retrieveAuditLog(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_ASSET_AUDIT_LOG] Received request for audit ledger of asset ID: {}", id);

        return retrieveAssetAuditLogUseCase.execute(id);
    }

    private Mono<HttpResponse<Void>> control(UUID id, ControlAssetUseCase.Action action, String executor) {
        log.info("[ACTION: CONTROL_ASSET] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return controlAssetUseCase.execute(id, action, executor).thenReturn(HttpResponse.noContent());
    }
}
