# 0003: refresh-token rotation, reuse detection, and where revocation lives

Date: 2026-10-06 · Status: accepted

## Context

ADR 0002 defined the `refresh_tokens` table and rotation with token families. CLAUDE.md says refresh
tokens are "revocable (Redis)". Implementing `/auth/refresh` and `/auth/logout` forces two questions:
how exactly rotation behaves, and whether Redis is needed for refresh-token revocation.

## Decision

- **Rotation:** `POST /auth/refresh` locks the token row (`SELECT ... FOR UPDATE`), revokes it, sets
  `replaced_by`, and issues a successor in the same family with a fresh 7-day TTL (sliding expiry).
  The lock makes two parallel refreshes with one token serialize: the second sees a revoked token.
- **Reuse detection:** presenting a revoked token revokes every active token in its family and returns 401.
  The revocation commits even though the request fails (`noRollbackFor`). Other families (other devices)
  are untouched.
- **One error for every failure** (unknown, expired, revoked, reused): `401 Invalid refresh token`.
- **Logout** (`POST /auth/logout`) revokes the token's family and always returns 204 (idempotent).
- **Roles are re-read on refresh.** The active org is kept if the membership still exists; otherwise the
  user lands in the first org they joined.
- **Refresh-token revocation lives in Postgres, not Redis.** Every refresh already reads and locks the
  row in Postgres, so a Redis copy would add a second source of truth without saving a query.
  **Redis gets the job that needs it:** a short-lived denylist of access-token `jti`s, checked by the
  gateway, if access tokens ever need to die before their 15-minute expiry (Phase 2 decision).

## Consequences

- An idle session ends after 7 days; an active one can live indefinitely. An absolute family lifetime
  (e.g. 30 days) can be added later with one column.
- A legitimate client that races itself (two tabs refreshing at once) can trigger reuse detection and be
  logged out. That is the accepted trade-off of strict rotation (OAuth 2.0 Security BCP).
- After logout, the access token stays valid until it expires (at most 15 minutes).
- Expired and revoked rows accumulate; a cleanup job comes later.
