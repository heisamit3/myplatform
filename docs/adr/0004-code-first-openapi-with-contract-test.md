# 0004: code-first OpenAPI, guarded by a contract test

Date: 2026-10-06 · Status: accepted

## Context

CLAUDE.md requires an OpenAPI spec per service in `contracts/openapi/`. The frontend (and later the
gateway docs) will be generated from or checked against it. Two ways to keep spec and code in sync:
write the spec first and generate code from it (design-first), or generate the spec from the code.

## Decision

- **Code-first with springdoc-openapi** (`springdoc-openapi-starter-webmvc-api`, no Swagger UI).
  The running service serves the spec at `/v3/api-docs` and `/v3/api-docs.yaml` (public, like JWKS).
- Controllers carry the API docs: `@Operation` with a stable `operationId` (client codegen uses it),
  `@ApiResponse` per status code, `@SecurityRequirement` on Bearer endpoints, and `@SecurityRequirements`
  (empty) on public ones.
- An `OpenApiCustomizer` gives every 4xx/5xx response the RFC 9457 `ProblemDetail` body, because all
  errors are problem+json, including Spring Security's 401.
- **The exported file is committed** at `contracts/openapi/identity.yaml`. `OpenApiContractTests` fails
  when it no longer matches the code. Regenerate with
  `UPDATE_CONTRACTS=true ./gradlew test --tests '*OpenApiContractTests'`.
  Keys are sorted (`writer-with-order-by-keys`) and so are tags, so the file is deterministic.
- The spec passes `redocly lint` (recommended ruleset) with only the warnings listed below.

## Consequences

- An API change shows up as a contract diff in the same commit, which makes review easy.
  CI (Phase 4) gets this check for free because it runs the tests.
- Spec quality depends on annotations. Design-first is stronger for public APIs designed by several
  teams; it's overkill for a single-team skeleton.
- Accepted lint warnings: no `license` in `info`, a `localhost` server URL (local direct access),
  and the JWKS operation has no 4xx response.
