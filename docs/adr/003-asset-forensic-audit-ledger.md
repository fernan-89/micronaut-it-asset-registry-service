# ADR-003: Asset Forensic Audit Ledger and DTO Projection

## Status
Accepted

## Context
An IT asset registry is only trustworthy if every change to an asset can be attributed: who moved
a server to a new rack, who pulled a laptop into maintenance, who decommissioned a switch. The
Hash Token Registry established the platform pattern (ADR-003 there): an immutable, append-only
audit trail projected through a dedicated DTO. The IT Asset Registry Service Domain adopts the same
pattern for its Control Record (`Asset`), catalog task **AST-02 — Audit Trail Ledger
Implementation**.

## Decision

### The ledger lives inside the aggregate
`Asset` owns an ordered list of `AssetAuditEntry(occurredAt, action, executor, fromStatus,
toStatus, detail)`. Every mutating behavior of the aggregate (`createNew`, `updateInfo`, `assign`,
`changeStatus` and its `markReady/deploy/startMaintenance/decommission` shortcuts) appends exactly
one entry and **returns it**. The application layer hands that same entry to the repository, so the
state change and its audit record are persisted by **one atomic MongoDB update**
(`$set` + `$push` on `auditTrail`) — the ledger can never diverge from the asset state, and there is
no window in which a crash could leave a change unaudited.

### Executor is mandatory
`X-Executor` is required on every mutation (ADR-013). The domain rejects a blank executor with
`IllegalArgumentException` (HTTP 400), so an anonymous change is structurally impossible.

### Append-only, never rewritten
There is no repository method that edits or removes a ledger entry; the aggregate exposes
`getAuditTrail()` as an unmodifiable view. Decommissioning keeps the asset and its full history in
the collection forever (no physical delete).

### DTO projection
`GET /it-asset-registry/v1/{id}/audit-log/retrieve` projects the ledger through
`AssetAuditEntryResponse` (statuses flattened to their names), keeping the pure domain model off the
HTTP boundary (ADR-001).

## Consequences
- Positive: attribution and status history are guaranteed by construction; one round-trip per
  mutation; the ledger is queryable without a second collection or join.
- Negative: the ledger grows inside the asset document (bounded by the number of lifecycle events
  of one physical asset, far below MongoDB's 16 MB document limit in practice). If an asset ever
  accumulates an unusual event volume, the ledger can be split into its own collection behind the
  same `AssetRepository` port without touching the domain or the API.
