# 0006: api-gateway routing, edge auth and Spring versions

Date: 2026-10-06 · Status: accepted

## Context

The gateway is the browser's only entry point. It needs routes to identity-service, token validation,
and CORS for the Vite dev server (`localhost:5173`). CLAUDE.md asks for the latest stable Spring Boot
with the matching Spring Cloud release train.

## Decision

- **Versions:** Spring Boot **4.0.8** + Spring Cloud **2025.1.3** (Gateway 5.0.3, reactive / WebFlux).
  The latest stable train, 2025.1, supports Boot 4.0 only. Spring Cloud 2026.0 (Boot 4.1) is still a
  milestone. identity-service stays on Boot 4.1.1: each service pins its own versions.
  Move the gateway to Boot 4.1 when Spring Cloud 2026.0.0 is GA.
- **Routes keep the service's paths:** `/auth/**`, `/me`, `/orgs/**` go to identity-service unchanged
  (no `/api/identity` prefix + StripPrefix). The browser sees the same paths as the OpenAPI spec.
  Each new service claims its own top-level paths (e.g. `/notifications/**`), and a clash shows up in
  review because all routes live in one `application.yaml`.
- **Edge auth:** the gateway is an OAuth2 resource server. It fetches identity-service's JWKS (cached,
  refetched on an unknown `kid`) and checks the RS256 signature, `exp` and `iss`. Only `/auth/**`
  and the probes are public. The `Authorization` header is forwarded unchanged, so services
  validate again (defense in depth), with no trusted "X-User-Id" headers.
- **CORS lives in Spring Security**, not in the gateway's `globalcors`. Security's CORS filter runs before
  authentication, so preflights get answered and 401s carry CORS headers the app can read.
  Credentials are allowed (the refresh-token cookie, see the next ADR). Origins come from `CORS_ALLOWED_ORIGINS`.
- **Readiness doesn't include downstream services:** one slow service must not pull the whole edge out of
  rotation.

## Consequences

- Two Spring Boot minor versions in the repo until Spring Cloud catches up.
- Every request is validated twice. Cheap: verifying an RS256 signature takes microseconds, and neither side
  calls identity-service per request.
- Tests don't need identity-service: a JDK `HttpServer` serves a test JWKS and echoes routed requests.
