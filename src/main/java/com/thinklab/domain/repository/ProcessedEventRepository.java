package com.thinklab.domain.repository;

import reactor.core.publisher.Mono;

/**
 * Inbox of events this service has already handled. Delivery on the event backbone is at-least-once (kit
 * ADR-003), so a consumer checks here before acting and records the event once it has succeeded; a
 * redelivered event is then acknowledged without side effects.
 */
public interface ProcessedEventRepository {

    Mono<Boolean> isProcessed(String eventKey);

    /** Idempotent: recording an already recorded key is not an error. */
    Mono<Void> markProcessed(String eventKey);
}
