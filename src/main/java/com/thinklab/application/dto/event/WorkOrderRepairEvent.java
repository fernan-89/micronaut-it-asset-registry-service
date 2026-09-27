package com.thinklab.application.dto.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code it-hardware-maintenance}'s {@code repair-started}/{@code repair-completed}
 * outbox events (that service's ADR-034). Field-identical to the producer's own
 * {@code WorkOrderRepairEvent} record - kept as a separate type here rather than a shared library
 * class, since the event backbone's contract is the JSON shape, not a shared Java type.
 */
public record WorkOrderRepairEvent(UUID workOrderId, UUID organisationId, UUID assetId, Instant occurredAt) {
}
