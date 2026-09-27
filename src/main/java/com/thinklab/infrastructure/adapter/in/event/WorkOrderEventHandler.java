package com.thinklab.infrastructure.adapter.in.event;

import com.thinklab.application.dto.event.WorkOrderRepairEvent;
import com.thinklab.application.usecase.ControlAssetUseCase;
import com.thinklab.domain.exception.AssetNotFoundException;
import com.thinklab.domain.exception.InvalidAssetStatusException;
import com.thinklab.domain.repository.ProcessedEventRepository;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Pure reaction to {@code it-hardware-maintenance}'s {@code repair-started}/{@code repair-completed}
 * events (that service's ADR-034): moves the affected Asset into/out of {@code MAINTENANCE} using the
 * existing {@link ControlAssetUseCase} - no new Asset domain logic, since both actions and the FSM
 * rule that only {@code DEPLOYED} enters {@code MAINTENANCE} already existed. Deliberately has zero
 * NATS imports - the only class touching {@code io.nats.client.*} is
 * {@link JetStreamAssetEventSubscriber}, which parses nothing itself and only calls
 * {@link #handle(String, String)}.
 */
@Singleton
public class WorkOrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderEventHandler.class);
    static final String REPAIR_STARTED_SUBJECT = "thinklab.it-hardware-maintenance.workorder.repair-started";
    static final String REPAIR_COMPLETED_SUBJECT = "thinklab.it-hardware-maintenance.workorder.repair-completed";

    private final ObjectMapper objectMapper;
    private final ControlAssetUseCase controlAssetUseCase;
    private final ProcessedEventRepository processedEvents;

    public WorkOrderEventHandler(ObjectMapper objectMapper, ControlAssetUseCase controlAssetUseCase, ProcessedEventRepository processedEvents) {
        this.objectMapper = objectMapper;
        this.controlAssetUseCase = controlAssetUseCase;
        this.processedEvents = Objects.requireNonNull(processedEvents, "Infrastructure constraint violated: ProcessedEventRepository cannot be null.");
    }

    public Mono<Void> handle(String subject, String payloadJson) {
        Objects.requireNonNull(subject, "Infrastructure constraint violated: subject cannot be null.");
        Objects.requireNonNull(payloadJson, "Infrastructure constraint violated: payloadJson cannot be null.");

        return Mono.fromCallable(() -> objectMapper.readValue(payloadJson, WorkOrderRepairEvent.class))
                .flatMap(event -> handleOnce(subject, event))
                .doOnError(e -> log.error("[EVENTS] Failed to handle [{}] payload: {}", subject, e.getMessage(), e));
    }

    /**
     * At-least-once delivery (kit ADR-003) means the same event can arrive again, e.g. when the ack of a
     * handled message is lost. The event is recorded only after the Asset control call has completed, so a
     * failure part-way still leaves it eligible for redelivery.
     */
    private Mono<Void> handleOnce(String subject, WorkOrderRepairEvent event) {
        String eventKey = subject + ":" + event.workOrderId();
        return processedEvents.isProcessed(eventKey)
                .flatMap(done -> {
                    if (done) {
                        log.info("[EVENTS] Duplicate delivery of [{}] ignored: already handled.", eventKey);
                        return Mono.<Void>empty();
                    }
                    return applyAction(subject, event).then(Mono.defer(() -> processedEvents.markProcessed(eventKey)));
                });
    }

    /**
     * Fail-open on a state mismatch: if the Asset is not in the expected source status (a redelivered
     * event, or an operator already moved it by hand), the resulting domain exception is logged and
     * swallowed rather than crashing the consumer or blocking the next message.
     */
    private Mono<Void> applyAction(String subject, WorkOrderRepairEvent event) {
        boolean started = REPAIR_STARTED_SUBJECT.equals(subject);
        ControlAssetUseCase.Action action = started ? ControlAssetUseCase.Action.MAINTENANCE : ControlAssetUseCase.Action.DEPLOY;
        String executor = "system:it-hardware-maintenance." + (started ? "repair-started" : "repair-completed");

        return controlAssetUseCase.execute(event.assetId(), action, executor)
                .onErrorResume(this::isRecoverable, e -> {
                    log.warn("[EVENTS] Could not apply [{}] to asset [{}]: {}", action, event.assetId(), e.getMessage());
                    return Mono.empty();
                });
    }

    private boolean isRecoverable(Throwable error) {
        return error instanceof InvalidAssetStatusException || error instanceof AssetNotFoundException;
    }
}
