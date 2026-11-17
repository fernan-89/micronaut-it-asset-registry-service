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
- **Note on events:** an event that loses a race with a person's change gets a 409 and the JetStream subscriber's existing error path (a negative acknowledgement, so
  the event is delivered again) applies; the retry reads the asset afresh and decides on the current status.
