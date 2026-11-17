# ADR-029: The By-Id Routes Are Scoped to the Tenant

## Status
Accepted (2026-11-17). Part of the tenant audit of the platform (thinklab-platform `docs/TENANT-AUDIT-2026-11.md`); same rule as workflow-approval ADR-037 and change management ADR-038.

## Context
Every route of an asset that takes the id (`{id}/retrieve`, `update`, `assignment/update`, `control/*`, `audit-log/retrieve`) found the asset by id alone. The cross-tenant probe (platform `scripts/e2e/tenant-probe.sh`) showed an asset of tenant X answering 200 to tenant Y, retrieve and audit log alike.

## Decision
- Every by-id route takes `X-Tenant-Id` (required, 400 if blank) and finds the asset only in that tenant: another tenant's asset is the same as none (404 `AssetNotFoundException`), nothing is read or written. The repository has no unscoped `findById` any more.
- The repair events of hardware maintenance already carry `organisationId`; the consumer passes it to the control use case, so an event can only move an asset of its own tenant.
- Callers: discovery sends the tenant on its update of a matched asset (merged first); the web and the gateway already send it on every call.

## Consequences
- Positive: a UUID is no longer enough to read or change another tenant's asset, from a request or from an event.
- Negative: a client that sends no tenant on a by-id route now gets a 400.
