# ADR-027: Tenant-Configurable Specification Schema Validation via ci-type-catalog-service

## Status
Accepted — reintroduces `ERR-AST-00422`, retired by [ADR-019](019-http-409-for-state-conflicts.md), for
a different class of failure than the one ADR-019 removed it for.

## Context
Journey 10 introduces `ci-type-catalog-service`: tenants author a JSON Schema per
`Asset.AssetCategory`, lifecycle-managed as `DRAFT -> ACTIVE -> INACTIVE -> ACTIVE`, with at most one
`ACTIVE` schema per `(organisationId, category)`. This Service Domain needs to actually enforce those
schemas against the `specifications` payload asset owners submit, or the catalog is purely decorative.

Two integration points are affected: `InitiateAssetUseCase` (on creation) and `UpdateAssetUseCase` (on
update) — both are already-public, 100%-covered, already-shipped use cases, so the change has to be
surgical and strictly additive for every tenant that hasn't configured a schema.

ADR-019 retired `ERR-AST-00422` because it was being used *incorrectly* — to report an FSM violation,
which depends on the aggregate's current state and is therefore a 409 by that ADR's own rule. A
`specifications` payload that fails schema validation is the opposite case: whether it's valid depends
only on the payload's content and the tenant's configured schema, never on the Asset's lifecycle state.
That is exactly the condition ADR-019 reserved for 422. Retiring the old, incorrect use of the code does
not preclude this new, correct one.

## Decision
1. **Two new outbound ports**: `CiTypeCatalogPort.fetchActiveSchema(organisationId, category)` returns
   `Mono<Optional<String>>` — `Optional.empty()` means "no `ACTIVE` schema for this tenant+category, skip
   validation," never an error signal. `SpecificationValidatorPort.validate(jsonSchema, specifications)`
   returns the list of human-readable violation messages (empty = conforms), implemented via
   `com.networknt:json-schema-validator` — this service's own copy of the same library
   `ci-type-catalog-service` uses to syntax-check authored schemas (ADR-031 of that service), not a
   shared JAR.
2. **Fails open on every failure mode of the catalog lookup**, not only "not configured": the catalog's
   own 404 and any other HTTP error, timeout, or connection failure all resolve to `Optional.empty()` in
   `CiTypeCatalogServiceAdapter`. This is a brand-new, optional, bolt-on dependency for an
   already-public, core-path service; a hard failure every time it hiccups would be a severe
   availability regression for a dependency that didn't exist before this journey. Same posture already
   proven live elsewhere on the platform by `NatsStreamInitializer` and `RevocationPoller` (both
   "fail-open" by design, per `THINKLAB-SESSION-GUIDE.md`). Accepted trade-off: a brief catalog outage
   silently skips validation rather than blocking Asset writes. A short timeout
   (`integrations.ci-type-catalog.read-timeout: 2s`, `connect-timeout: 1s`) keeps that fail-open path
   fast rather than stalling the request. The `thinklab.ci-type-catalog.fail-closed` toggle
   (env `CI_TYPE_CATALOG_FAIL_CLOSED`, default `false`) is the escape hatch for strict enforcement: when
   `true`, a failed lookup (any non-404 HTTP error, timeout, unreachable catalog) raises
   `CiTypeCatalogUnavailableException` and the write is refused with **503 `ERR-AST-00503`** (retryable;
   not a 4xx, because the request itself is fine). "No schema configured" (the catalog's 404) skips
   validation in both modes. The toggle is per deployment, not per tenant.
3. **Validation runs once, on the write, never retroactively.** Activating or editing a
   `TypeDefinition` in the catalog never revalidates Assets already on record — it only affects the next
   `initiate`/`update` call. This is a deliberate scope boundary for this journey, not a gap: retroactive
   revalidation would need its own reconciliation job and failure-handling story, out of scope here.
4. **Placement in each use case**: in `InitiateAssetUseCase`, the catalog lookup + validation run after
   the duplicate-serial-number check and before `HashServicePort.generateSovereignId` — a request that's
   going to be rejected never spends a Sovereign ID. In `UpdateAssetUseCase`, they run after
   `findById`/`switchIfEmpty` and before `Asset.updateInfo` — a rejected update never produces a partial
   write.
5. **`ERR-AST-00422` maps to HTTP 422 Unprocessable Entity** in `GlobalExceptionHandler`, carrying a
   `violations` array extension member on the RFC 7807 problem document (same pattern as the existing
   `debug_info` extension on generic failures) — distinct from the 409 family `ERR-AST-00409` still
   covers.

## Consequences
- Positive: schema enforcement is live and strict when a tenant opts in, with zero behavior change for
  every tenant that hasn't configured a schema for a category — the integration is purely additive.
- Positive: the platform's fail-open precedent for optional, bolt-on dependencies is upheld, so a
  catalog outage degrades to "no validation" rather than taking down Asset writes.
- Negative: a tenant that *has* configured and activated a schema gets no signal if the catalog happens
  to be unreachable at the moment of a write — the request silently succeeds unvalidated. Accepted for
  v1 by default; deployments that need strict enforcement set `fail-closed` (see decision 2) and get a
  503 instead.
- Negative: `specifications` entered before a schema existed, or before the relevant `TypeDefinition`
  was activated, remain on record even if they'd now fail validation. No reconciliation job exists; a
  future journey would need to add one if retroactive enforcement is ever required.
