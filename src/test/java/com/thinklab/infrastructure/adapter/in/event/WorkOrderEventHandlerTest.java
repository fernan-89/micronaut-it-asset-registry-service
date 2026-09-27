package com.thinklab.infrastructure.adapter.in.event;

import com.thinklab.application.dto.event.WorkOrderRepairEvent;
import com.thinklab.application.usecase.ControlAssetUseCase;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.exception.InvalidAssetStatusException;
import com.thinklab.domain.repository.ProcessedEventRepository;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkOrderEventHandlerTest {

    private static final String STARTED = "thinklab.it-hardware-maintenance.workorder.repair-started";
    private static final String COMPLETED = "thinklab.it-hardware-maintenance.workorder.repair-completed";

    @Mock private ObjectMapper objectMapper;
    @Mock private ControlAssetUseCase controlAssetUseCase;
    @Mock private ProcessedEventRepository processedEvents;

    private WorkOrderEventHandler handler;
    private WorkOrderRepairEvent event;

    @BeforeEach
    void setUp() {
        handler = new WorkOrderEventHandler(objectMapper, controlAssetUseCase, processedEvents);
        event = new WorkOrderRepairEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }

    @Test
    @DisplayName("repair-started moves the asset into MAINTENANCE")
    void repairStartedAppliesMaintenance() throws Exception {
        when(objectMapper.readValue(eq("payload"), eq(WorkOrderRepairEvent.class))).thenReturn(event);
        when(controlAssetUseCase.execute(eq(event.assetId()), eq(ControlAssetUseCase.Action.MAINTENANCE), any())).thenReturn(Mono.empty());
        when(processedEvents.isProcessed(STARTED + ":" + event.workOrderId())).thenReturn(Mono.just(false));
        when(processedEvents.markProcessed(STARTED + ":" + event.workOrderId())).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(STARTED, "payload")).verifyComplete();

        verify(controlAssetUseCase).execute(event.assetId(), ControlAssetUseCase.Action.MAINTENANCE, "system:it-hardware-maintenance.repair-started");
        verify(processedEvents).markProcessed(STARTED + ":" + event.workOrderId());
    }

    @Test
    @DisplayName("repair-completed moves the asset back to DEPLOY")
    void repairCompletedAppliesDeploy() throws Exception {
        when(objectMapper.readValue(eq("payload"), eq(WorkOrderRepairEvent.class))).thenReturn(event);
        when(controlAssetUseCase.execute(eq(event.assetId()), eq(ControlAssetUseCase.Action.DEPLOY), any())).thenReturn(Mono.empty());
        when(processedEvents.isProcessed(COMPLETED + ":" + event.workOrderId())).thenReturn(Mono.just(false));
        when(processedEvents.markProcessed(COMPLETED + ":" + event.workOrderId())).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(COMPLETED, "payload")).verifyComplete();

        verify(controlAssetUseCase).execute(event.assetId(), ControlAssetUseCase.Action.DEPLOY, "system:it-hardware-maintenance.repair-completed");
    }

    @Test
    @DisplayName("a redelivered event that was already handled is acknowledged without a second Asset call")
    void handleDuplicate() throws Exception {
        when(objectMapper.readValue(eq("payload"), eq(WorkOrderRepairEvent.class))).thenReturn(event);
        when(processedEvents.isProcessed(STARTED + ":" + event.workOrderId())).thenReturn(Mono.just(true));

        StepVerifier.create(handler.handle(STARTED, "payload")).verifyComplete();

        verifyNoInteractions(controlAssetUseCase);
        verify(processedEvents, never()).markProcessed(any());
    }

    @Test
    @DisplayName("a malformed payload is propagated as an error, never applied")
    void handleMalformedPayload() throws Exception {
        when(objectMapper.readValue(eq("garbage"), eq(WorkOrderRepairEvent.class))).thenThrow(new java.io.IOException("bad json"));

        StepVerifier.create(handler.handle(STARTED, "garbage")).expectError(java.io.IOException.class).verify();
    }

    @Test
    @DisplayName("a state-mismatch (InvalidAssetStatusException) is recoverable: logged, swallowed, still marked processed")
    void invalidAssetStatusIsRecoverable() throws Exception {
        when(objectMapper.readValue(eq("payload"), eq(WorkOrderRepairEvent.class))).thenReturn(event);
        when(controlAssetUseCase.execute(any(), any(), any())).thenReturn(Mono.error(new InvalidAssetStatusException("wrong state")));
        when(processedEvents.isProcessed(any())).thenReturn(Mono.just(false));
        when(processedEvents.markProcessed(any())).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(STARTED, "payload")).verifyComplete();

        verify(processedEvents).markProcessed(STARTED + ":" + event.workOrderId());
    }

    @Test
    @DisplayName("a missing asset (AssetNotFoundException) is also recoverable")
    void assetNotFoundIsRecoverable() throws Exception {
        when(objectMapper.readValue(eq("payload"), eq(WorkOrderRepairEvent.class))).thenReturn(event);
        when(controlAssetUseCase.execute(any(), any(), any())).thenReturn(Mono.error(new AssetNotFoundException(event.assetId())));
        when(processedEvents.isProcessed(any())).thenReturn(Mono.just(false));
        when(processedEvents.markProcessed(any())).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(COMPLETED, "payload")).verifyComplete();
    }

    @Test
    @DisplayName("an unexpected failure is propagated and never marked processed, so redelivery gets another chance")
    void unexpectedFailurePropagates() throws Exception {
        when(objectMapper.readValue(eq("payload"), eq(WorkOrderRepairEvent.class))).thenReturn(event);
        when(controlAssetUseCase.execute(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(processedEvents.isProcessed(any())).thenReturn(Mono.just(false));

        StepVerifier.create(handler.handle(STARTED, "payload")).expectError(IllegalStateException.class).verify();

        verify(processedEvents, never()).markProcessed(any());
    }

    @Test
    @DisplayName("subject, payloadJson and the inbox are null-checked")
    void nullGuards() {
        assertThrows(NullPointerException.class, () -> new WorkOrderEventHandler(objectMapper, controlAssetUseCase, null));
        assertThrows(NullPointerException.class, () -> handler.handle(null, "payload"));
        assertThrows(NullPointerException.class, () -> handler.handle(STARTED, null));
    }
}
