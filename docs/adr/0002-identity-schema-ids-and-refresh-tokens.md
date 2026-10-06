# 0002: identity schema: UUIDv7 keys and hashed, rotating refresh tokens

Date: 2026-10-06 · Status: accepted

## Context

identity-service needs users, organizations (tenants), memberships and refresh tokens.
We need to choose primary keys and decide how refresh tokens are stored and rotated.

## Decision

- **Primary keys are `uuid DEFAULT uuidv7()`** (built into PostgreSQL 18). IDs can be exposed in URLs and
  events without leaking counts. They are time-ordered, so B-tree inserts append at the end, unlike random
  UUIDv4.
- **memberships** has the primary key `(org_id, user_id)`, leading with the tenant column. A second index on
  `user_id` serves "my orgs".
- **Emails are stored lower-case**, enforced by a `CHECK`, and are `UNIQUE`.
- **Refresh tokens:** an opaque random string of 256 bits. Only its **SHA-256** hash is stored, unique and
  looked up directly. bcrypt is unnecessary for high-entropy secrets, and it would make lookup by token
  impossible.
- **Rotation with reuse detection:** every refresh revokes the token (`revoked_at`, `replaced_by`) and issues a
  new one with the same `family_id`. If a revoked token is presented again, the whole family is revoked
  (OAuth 2.0 Security BCP).

## Consequences

- PostgreSQL 18 is required. Testcontainers uses the same pgvector pg18 image as compose.
- The application must lower-case emails before insert and lookup.
- Fast revocation checks still go through Redis, as in CLAUDE.md. This table is the durable record.
