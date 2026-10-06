# 0007: Rate limiting login and register in the gateway (Redis token buckets)

Date: 2026-10-06 · Status: accepted

## Context

`/auth/login` invites password guessing and `/auth/register` invites account spam. bcrypt makes each
guess slow for an attacker, but also costs us CPU per request. The gateway can run with several replicas, so
counters must be shared, not in memory.

## Decision

- Spring Cloud Gateway's `RequestRateLimiter` with `RedisRateLimiter`: a **token bucket** per key, updated
  atomically by a Lua script in Redis. Keys expire on their own.
- Bucket: 60 tokens, refilled at 1 token/s. Login costs 6 tokens (**10 per minute**), register costs 12
  (**5 per minute**). Other `/auth/*` endpoints are not limited: refresh tokens are 256-bit random, so they
  can't be guessed.
- **Key = client IP.** There is no user before login, and a per-email key would need the body parsed in
  the gateway. Behind proxies, the IP comes from `X-Forwarded-For`, trusting exactly
  `RATE_LIMIT_TRUSTED_PROXIES` hops from the right. Trusting more would let clients choose their own key.
- **Fails open:** if Redis is down, requests pass (the limiter logs an error). Redis isn't part of
  `/ready` either. Login keeps working without Redis; it just isn't limited for that time.
- Over the limit: `429` with `X-RateLimit-*` headers (`Remaining` counts tokens, not requests).

## Consequences

- In compose, every request from the host arrives from Docker's bridge address (172.x.0.1), so all local
  clients share one bucket. That's fine for development.
- Kubernetes (Phase 5): set `RATE_LIMIT_TRUSTED_PROXIES=1` behind Traefik, or everyone shares the proxy's bucket.
- Per-account lockout (many IPs, one email) and a problem+json body for 429 are possible later upgrades.
