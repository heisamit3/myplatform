# 0011: identity-service publishes events after commit (best effort, outbox later)

Date: 2026-10-06 · Status: delivery superseded by ADR 0014 (transactional outbox); JSON-as-String, record keys and topic declaration still apply

## Context

`identity.user.registered.v1` must reach Kafka when a user is created. The user row lives in Postgres and
the event goes to Kafka. Without a distributed transaction, no single step can write both atomically
(the "dual write" problem). The transactional outbox solves it but is a Phase 3 stretch goal. We need a
simple first version whose failure modes are known.

## Decision

- `AuthService.register` is `@Transactional` and publishes an in-process Spring event (`UserRegistered`).
- `DomainEventRelay` listens with `@TransactionalEventListener(AFTER_COMMIT)`: **Kafka never sees an event
  for a row that was rolled back** (e.g. the duplicate-email race).
- The listener is `@Async`, so the HTTP response doesn't wait for Kafka. `send()` waits at most
  `max.block.ms` (10 s) for broker metadata. Failures are **logged, not thrown** (event name, eventId and
  key only, because the payload holds an email address).
- **We serialize JSON to a String ourselves** (Jackson) and use `StringSerializer`. Spring's JSON
  serializer adds `__TypeId__` headers with Java class names, which mean nothing to a Node consumer
  and couple consumers to our package layout.
- **Record key = the entity the event is about** (`userId` here, later `orgId` for org events). Kafka
  keeps order per partition, and the key picks the partition.
- Topics are declared by the producer as `NewTopic` beans (3 partitions, replication from
  `KAFKA_REPLICATION_FACTOR`), because the broker has auto-create off (ADR 0009).
- Producer defaults are kept: `acks=all`, idempotence on, `delivery.timeout.ms` 120 s (rides out a
  broker restart). `buffer.memory` lowered to 4 MB for the 384 MB container.

## Consequences

- **An event can be lost** if the process dies after commit but before the send, or if Kafka stays
  unreachable longer than the delivery timeout. Verified: with Kafka stopped, registration still returns
  201 in about 0.4 s, and the event is logged as "not published". The transactional outbox (stretch goal)
  removes this gap by writing the event in the same DB transaction.
- At-least-once on the wire: a producer retry can't duplicate (idempotent producer), but a future outbox
  relay can. Consumers dedupe on `eventId` either way.
- If Kafka is down when identity-service starts, the topic isn't created until the next start. Compose
  starts Kafka first when the `events` profile is on (`depends_on` with `required: false`).
- Tests run against a real Kafka (Testcontainers, same image) and validate each published event against
  `contracts/events/` (`UserRegisteredEventTests`).
