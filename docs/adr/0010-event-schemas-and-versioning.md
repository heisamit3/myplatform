# 0010: Event contracts as strict JSON Schemas; tolerant consumers

Date: 2026-10-06 · Status: accepted

## Context

Kafka events are an API between services, just like REST. CLAUDE.md fixes the envelope fields and the
topic naming (`<service>.<entity>.<event>.v<version>`). Still open: where the contract lives, how strict
it is, how versions evolve, and what `orgId` is for events that don't belong to an org.

## Decision

- **One JSON Schema (draft 2020-12) per event and version** in `contracts/events/`, plus
  `envelope.v1.schema.json`, which every event schema references with `$ref`.
- The envelope's `type` is `<service>.<entity>.<event>` **without** the version. The topic is
  `<type>.v<version>`, so a consumer can derive one from the other.
- **`orgId` is required but nullable.** User-level facts like `identity.user.registered` happen before
  any org exists, so they carry `null` explicitly. A missing field is always a bug.
- **`occurredAt` must end in `Z`** (UTC), not just be a valid date-time.
- **Producer side strict:** `additionalProperties: false` on the envelope and payloads. A producer test
  validates real events against the schema, so an accidentally serialized field (say, a password hash)
  fails the build instead of leaking to every consumer.
- **Consumer side tolerant:** consumers ignore unknown fields and don't validate against the schema at
  runtime. Adding an optional field is therefore a compatible change within the same version
  (update the schema first, then the producer).
- **Breaking change** (removing/renaming a field, changing its meaning or type) = **new version = new topic**.
  The producer publishes both versions until every consumer has moved.
- `./scripts/check-event-schemas.sh` (ajv via npx) checks that schemas compile, `examples/` are valid
  and `invalid/` are rejected.

## Consequences

- `identity.user.registered.v1` contains the email address (personal data): the welcome email needs it,
  and the alternative (notification-service calling identity-service back) would couple the services
  synchronously. Topic retention is therefore a privacy setting too.
- No schema registry (Confluent/Apicurio): it would be another long-running container. Schemas in the repo
  plus contract tests cover a monorepo. A registry is a possible later upgrade.
