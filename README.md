| `ERR-AST-00503` | 503 | ci-type-catalog-service lookup failed while `thinklab.ci-type-catalog.fail-closed=true` (ADR-027); retry |
# Thinklab IT Asset Registry Service

**Version:** v1.0.0-BIAN

**Status:** Reference implementation (ThinkLab portfolio project)

## Overview

The Thinklab IT Asset Registry Service is the authoritative inventory of an organisation's IT
assets — laptops, servers, network gear, storage arrays, IoT sensors, virtual machines and software
licences — from registration to decommissioning. It implements the BIAN-aligned `it-asset-registry`
Service Domain (ADR-013): the `Asset` is the Control Record and every route follows the
`/{control-record-id}/{behavior-qualifier}` convention (`initiate`, `retrieve`, `update`,
`assignment`, `control`, `audit-log`) instead of ad-hoc REST verbs.

Every asset is scoped to an Organisation from the Party Reference Data Directory (`X-Tenant-Id`),
receives its sovereign UUID from the Hash Token Registry, moves through an explicit lifecycle state
machine, and carries an immutable forensic audit ledger of every change (ADR-003, ADR-017).

An optional event consumer (`thinklab.events.enabled`) reacts to the IT Hardware Maintenance
Service's `repair-started`/`repair-completed` events, moving the affected Asset into and out of
`MAINTENANCE` automatically as a WorkOrder's repair begins and is verified fixed (ADR-026) — no
synchronous call between the two services, ever.

On `initiate` and `update`, the `specifications` payload is validated against the tenant's `ACTIVE`
JSON Schema for the asset's category, fetched synchronously from `ci-type-catalog-service` — fully
optional and backward-compatible: no schema configured, or the catalog unreachable, both skip
validation rather than blocking the write (ADR-027).

Built with Java 21 and Micronaut 4.4.2 on a strict Hexagonal Architecture and a fully reactive
stack (Project Reactor, reactive MongoDB driver).

## Technology Stack

* **Runtime:** Java 21 LTS
* **Framework:** Micronaut 4.4.2 (AOT optimized, reflection-free DI and Serde)
* **Reactive Engine:** Project Reactor (Mono / Flux)
* **Persistence:** Reactive MongoDB (`thinklab_asset_db`, collection `assets`), BSON UUID standard representation
* **Events:** kit outbox/JetStream consumer (`thinklab.events.enabled`) — reacts to IT Hardware Maintenance's `repair-started`/`repair-completed` events (ADR-026)
* **Observability:** W3C Trace Context, SLF4J/Logback, Reactor MDC bridge
* **Containerization:** Google Distroless (nonroot), read-only root filesystem
* **Testing:** JUnit 5, Mockito, Reactor Test (exhaustive FSM matrix, use cases, controller, adapter, handler)
* **Documentation:** OpenAPI 3.0 / Swagger generated at compile time

## Domain Model

```text
Asset {
  id, organisationId, name, category, serialNumber, specifications{},
  status, assignedToUserId?, locationId?, createdAt, updatedAt,
  auditTrail[ { occurredAt, action, executor, fromStatus?, toStatus, detail } ]
}
category: LAPTOP | DESKTOP | SERVER | NETWORK_DEVICE | STORAGE_ARRAY | PERIPHERAL |
          MOBILE_DEVICE | IOT_SENSOR | VIRTUAL_MACHINE | SOFTWARE_LICENSE
```

### Lifecycle (ADR-017)

```text
PROVISIONED -> READY -> DEPLOYED <-> MAINTENANCE
                  ^          |            |
                  +----------+------------+      (return to stock)
any non-terminal -> DECOMMISSIONED (terminal, no exit, no DELETE)
```

* An asset cannot be `DEPLOYED` without a location, and a deployed asset cannot lose its location.
* `DECOMMISSIONED` blocks every further mutation.

## BIAN Behavior Qualifier Contract (`/it-asset-registry/v1`)

`X-Tenant-Id` (Organisation UUID) is mandatory on `initiate` and the collection `retrieve`;
`X-Executor` is mandatory on every mutation and is recorded in the audit ledger. There is no `DELETE`.

| Behavior Qualifier | Method & Path |
|---|---|
| initiate | `POST /it-asset-registry/v1/initiate` |
| retrieve (single) | `GET /it-asset-registry/v1/{id}/retrieve` |
| retrieve (collection, filters `status`, `category`) | `GET /it-asset-registry/v1/retrieve` |
| update | `PUT /it-asset-registry/v1/{id}/update` |
| assignment/update | `PUT /it-asset-registry/v1/{id}/assignment/update` |
| control/ready, deploy, maintenance, decommission | `PUT /it-asset-registry/v1/{id}/control/{action}` |
| audit-log/retrieve | `GET /it-asset-registry/v1/{id}/audit-log/retrieve` |

### Error catalog (RFC 7807, `error_code` field)

| error_code | HTTP | Meaning |
|---|---|---|
| `ERR-AST-00404` | 404 | Asset not found |
| `ERR-AST-00409` | 409 | Serial number already registered, illegal lifecycle transition or policy violation (state conflict) |
| `ERR-AST-00422` | 422 | `specifications` violate the tenant's configured JSON Schema for the asset's category (ADR-027); carries a `violations` array |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

Example:

```bash
curl -X POST http://localhost:8083/it-asset-registry/v1/initiate \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: 6f1c7a52-3d0b-4a44-9c3e-0a7d1f6e2b10" \
  -H "X-Executor: admin-user-01" \
  -d '{"name":"Core Switch","category":"NETWORK_DEVICE","serialNumber":"SN-77","specifications":{"ports":"48"}}'
```

## Operational Procedures

```bash
# Build, run AOT optimizations and test
./gradlew clean build

# Start the service (default port 8083)
./gradlew run

# Container image
docker build -t thinklab-asset-registry-service:latest .
```

* **Health:** `http://localhost:8083/health`
* **Swagger UI:** `http://localhost:8083/swagger-ui`
* **Postman suite:** `docs/postman/` (lifecycle + negative/409 scenarios)

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MICRONAUT_SERVER_PORT` | `8083` | HTTP port |
| `MONGODB_URI` | `mongodb://localhost:27017/thinklab_asset_db` | MongoDB connection |
| `HASH_SERVICE_URL` | `http://localhost:8080` | Hash Token Registry base URL |
| `CI_TYPE_CATALOG_SERVICE_URL` | `http://localhost:8093` | ci-type-catalog-service base URL (specification schema lookup, ADR-027) |
| `THINKLAB_EVENTS_ENABLED` | `false` | Consume `repair-started`/`repair-completed` from NATS JetStream |
| `THINKLAB_EVENTS_NATS_URL` | `nats://localhost:4222` | NATS connection, when events are enabled |

## Architecture Decision Records

`docs/adr/`: 001 hexagonal reactive stack · 003 asset forensic audit ledger · 005 UUID identity
sovereignty · 013 BIAN service domain conventions · 017 asset lifecycle FSM (HTTP contract superseded by 019) · 019 HTTP 409 for state conflicts · 026 MAINTENANCE status driven by WorkOrder events ·
027 tenant-configurable specification schema validation via ci-type-catalog-service.

### Automated Tests

```bash
./gradlew test               # unit suite + 100% line/branch coverage gate (no Docker needed)
./gradlew integrationTest    # Testcontainers suite against a real MongoDB replica set (needs Docker)
./gradlew check              # both, as CI runs it
```

The integration suite (`src/integrationTest`, platform
[ADR-025](https://github.com/fernan-89/micronaut-hash-token-registry-service/blob/master/docs/adr/025-integration-tests-with-testcontainers.md))
runs the Asset aggregate through the repository against a real MongoDB: the specifications map and the forensic audit ledger through the POJO codec, every partial update appending its ledger entry in order, tenant-scoped filtering by status and category, the tenant-scoped serial-number check, not-found handling, and the database taken from `mongodb.uri`.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
