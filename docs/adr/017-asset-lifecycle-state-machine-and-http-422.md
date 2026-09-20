# ADR-017: Asset Lifecycle State Machine and the HTTP 422 State-Conflict Contract

## Status
Accepted

## Context
The IT Asset Registry tracks a physical or logical asset from the moment it is registered until it
is retired. Two design questions had to be settled: what lifecycle is legal, and how an illegal move
is reported. Catalog task **AST-03 — HTTP 422 State Conflict Handler** and the roadmap deliverable
"Asset FSM, HTTP 422" require an explicit answer.

## Decision

### Lifecycle
`AssetStatus`: `PROVISIONED -> READY -> DEPLOYED <-> MAINTENANCE`, with a return-to-stock edge
(`DEPLOYED|MAINTENANCE -> READY`) and a terminal `DECOMMISSIONED` reachable from every non-terminal
state. `DECOMMISSIONED` has no exit and blocks every further mutation (update, assignment, control).
The rules live in `AssetStatus.canTransitionTo/validateTransitionTo`, mirroring `HashStatus` /
`OrganisationStatus` / `UserStatus` (ADR-013). `ControlAssetUseCase` always loads the aggregate and
lets the domain validate the move **before** any persistence call — never a blind partial write (the
bug class found during the Party Reference Data Directory's E2E validation).

### Deployment policy
An asset cannot be `DEPLOYED` without a `locationId`, and a `DEPLOYED` asset cannot have its
location cleared. The invariant is enforced in the aggregate (`changeStatus`, `assign`), so it holds
regardless of the entry point.

### HTTP 422 for state conflicts, 409 for identity conflicts
| Situation | Code | HTTP |
|---|---|---|
| Asset not found | `ERR-AST-00404` | 404 |
| Duplicate serial number in the same Organisation | `ERR-AST-00409` | 409 |
| Illegal transition, idempotent self-transition, deploy without location, mutation after decommission | `ERR-AST-00422` | 422 |
| Bean-validation / malformed identifier | `ERR-VALIDATION-00400` | 400 |

A duplicate serial number is a *collision with another resource* (409). An illegal transition is a
well-formed request that is *semantically impossible in the aggregate's current state* — the
textbook meaning of 422 Unprocessable Entity. Using a distinct status lets clients tell "you raced
another writer" from "this operation cannot happen now" without parsing the body. Every response is
an RFC 7807 problem document carrying `error_code`, identical in shape to the other Service Domains.

### Persistence
Raw reactive MongoDB driver with an explicit `PojoCodecProvider` registry (the Party Reference Data
Directory lesson: the default registry has no POJO codec). Transitions are single atomic
`$set` + `$push` updates (ADR-002, ADR-003).

## Consequences
- Positive: the lifecycle is exhaustively unit-tested as a full transition matrix; clients get a
  precise, machine-readable failure taxonomy.
- Negative: this service reports state conflicts as 422 while sibling domains (User, Organisation)
  use 409 for the same class of error. The difference is intentional and documented here; aligning
  the platform on one convention is a cross-service decision for a future ADR.
