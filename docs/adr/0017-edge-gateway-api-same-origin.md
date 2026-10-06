# 0017: Edge in Kubernetes: Traefik via Gateway API, web and API on one origin

Date: 2026-10-06 · Status: accepted

## Context

In Kubernetes the browser needs one way in to both the React app and api-gateway. CLAUDE.md already decides
Gateway API (not ingress-nginx, which is retired) with Traefik. Open questions: what is routed where, and how the
web app finds the API.

## Decision

- **Gateway API objects:** the Gateway API CRDs (standard channel, v1.6.1 = the version Traefik 3.7.13 is built
  against) and Traefik's chart, which creates the GatewayClass `traefik`. Our own `Gateway` `myplatform`
  (`infra/k8s/raw/gateway.yaml`, one HTTP listener on Traefik's entry point port 8000, routes from the same
  namespace only). Each service declares its paths in an `HTTPRoute` rendered by the shared chart (ADR 0016).
  Traefik runs with only the Gateway API provider (no Ingress, no Traefik CRDs).
- **One origin, routed by path:** `/auth/*`, `/me`, `/orgs/*` → api-gateway; everything else → web (nginx).
  Gateway API picks the most specific match, so the API paths win over web's `/`.
  - No CORS in the normal flow: the browser talks to a single origin.
  - The refresh cookie (`SameSite=Strict`, path `/auth`, ADR 0008) works without cross-site exceptions.
  - The web bundle is built with `VITE_API_URL=""` (relative URLs), so the same image works in every environment.
- **api-gateway stays the only API entry point.** Traefik does routing only. JWT validation, rate limits and
  the refresh-cookie handling stay in the gateway. Its `/health`, `/ready` and `/metrics` aren't routed, so they
  stay inside the cluster. `RATE_LIMIT_TRUSTED_PROXIES=1`: Traefik is the one proxy that appends to
  `X-Forwarded-For` (ADR 0007).
- **Local access:** Traefik's Service is `ClusterIP`, reached with `kubectl -n traefik port-forward svc/traefik 8088:80`.
  `http://localhost:8088` counts as a secure context in browsers, so the `Secure` cookie works without TLS.
  A `LoadBalancer` would need `minikube tunnel` (admin rights on Windows). On AWS it becomes a LoadBalancer with TLS.
- **web image:** multi-stage, Node builds, `nginxinc/nginx-unprivileged` (stable 1.30, UID 101, port 8080)
  serves. Hashed `/assets/*` are cached for a year, `index.html` is `no-cache`, and client-side routes fall back to
  `index.html`. nginx answers `/health` and `/ready` itself.

## Consequences

- Verified through the port-forward: SPA deep links → 200, register/login/`/me`/`POST /orgs`/refresh work, the
  refresh token stays in the cookie, and spoofed `X-Forwarded-For` headers don't get around the login limit.
- A new API service = more `route.paths` on api-gateway's side, not on Traefik's: the browser only ever reaches
  the gateway's paths.
- The Vite dev server (5173 → gateway 8080, cross-origin with CORS) is unchanged for daily development.
