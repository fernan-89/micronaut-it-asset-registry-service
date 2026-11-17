# ADR 028: Asset writes are guarded writes

## Status
Accepted (2026-11-17). Found by the platform race audit (ACTION-PLAN 0.5, `thinklab-platform/docs/race-audit.md`).

## Context
Every change read the asset, validated it in the domain and wrote it by `_id` only. Measured by `TransitionRaceIT` on the old code: ten "ready" of one
provisioned asset at once all answered OK, and a "ready" and a decommission at once both answered OK, so the stored status was whichever write landed
last while the trail held both entries. The same use case answers the maintenance events of the work orders (ADR-026), so a lost race there left an
asset in the wrong status.

## Decision
Every `update*` of the repository is a **guarded write** on the status the caller read (the `fromStatus` of the audit entry it carries, so no use case
changed): no match is `409 ERR-AST-00409` when the asset exists and `404` when it does not (ADR-019).

## Consequences
- **Positive:** one winner per race; the stored status and the trail always agree.
- **Note on events:** `WorkOrderEventHandler` is deliberately fail-open on a state mismatch (ADR-026): an `InvalidAssetStatusException` is logged and swallowed,
  the event acknowledged. A work-order event that now loses a race with a person's change is treated the same way as "an operator already moved it by
  hand", which is what that design already accepts; it is not redelivered. If the event must win such a race, that is a decision for ADR-026, not for this one.
