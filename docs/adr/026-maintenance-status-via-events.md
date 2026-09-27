# ADR-026: Asset MAINTENANCE Status Driven by WorkOrder Events, Not a Synchronous Call

## Status
Accepted

## Context
`micronaut-it-hardware-maintenance-service` (Journey 6) needs the affected Asset to move into
`MAINTENANCE` when a repair genuinely begins and back to service once verified fixed. The blueprint
requires this be event-driven, never a synchronous call between the two services — that service must
never block a WorkOrder mutation on this one being reachable, and this service must never be a
dependency of that one's request path.

This is the first time `it-asset-registry` consumes an event rather than only producing state for
others to read; it had zero NATS/event-backbone code before this change.

## Decision
- `it-hardware-maintenance` publishes `repair-started`/`repair-completed` outbox events (its own
  ADR-034) on `control/start` and `control/pass`. This service subscribes with
  `JetStreamAssetEventSubscriber` — the only class here touching `io.nats.client.*` — a single durable
  pull consumer (`it-asset-registry-worker`) filtered on the wildcard subject
  `thinklab.it-hardware-maintenance.workorder.*`, mirroring `notification-dispatch`'s
  `JetStreamNotificationSubscriber` shape exactly (retry-until-subscribed at startup, `ack`/`nak`/`term`
  by delivery count, no dead-letter subject in v1).
- `WorkOrderEventHandler` (zero NATS imports, pure reaction to a subject + JSON payload) reuses the
  **existing** `ControlAssetUseCase` unchanged: `Action.MAINTENANCE` for `repair-started`,
  `Action.DEPLOY` for `repair-completed`. No new Asset domain method was needed — per `AssetStatus`'s
  own FSM, only `DEPLOYED` ever enters `MAINTENANCE`, so "the state to return to" is unambiguous and
  already exactly what `Action.DEPLOY` does.
- **Fail-open on a state mismatch**: if the Asset is not `DEPLOYED` when `repair-started` arrives, or
  not `MAINTENANCE` when `repair-completed` arrives (a redelivered event, an operator already moved it
  by hand, or the Asset was deleted — not physically possible here, but not found is handled the same
  way), the resulting `InvalidAssetStatusException`/`AssetNotFoundException` is caught, logged, and the
  event is still marked processed — this is a legitimate "nothing to do" outcome, not a failure to retry.
  Any other exception propagates and is **not** marked processed, so JetStream redelivers it.
- `ProcessedEventRepository` (new, `processed_events` collection, same shape as
  notification-dispatch's) makes the consumer idempotent under at-least-once delivery (kit ADR-003).

## Consequences
- Positive: the two services stay fully decoupled at runtime; the Asset FSM's own invariants are
  reused as-is; adding a consumer required no change to `ControlAssetUseCase` or `Asset` itself.
- Negative: this service now depends on `io.nats:jnats` and the kit's event beans at runtime even
  though it publishes nothing itself — an acceptable, small addition (the beans are gated behind
  `thinklab.events.enabled`, off by default, same as every other service that has flirted with events
  without using them yet).
