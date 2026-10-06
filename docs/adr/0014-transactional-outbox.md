# 0014: Transactional outbox with a polling relay in identity-service

Date: 2026-10-06 · Status: accepted · Supersedes the delivery part of ADR 0011

## Context

ADR 0011 sent events after commit, best effort: if the process died between the commit and the send,
or Kafka stayed down longer than the delivery timeout, the event was lost for good (verified: with Kafka
stopped, the user was created and the welcome email never came). That's the "dual write" problem: one
step can't write to Postgres and Kafka atomically.

Options considered:

- **Polling outbox** (chosen): write the event to an `outbox_events` table in the same transaction as the
  change; a relay in the service polls it and sends to Kafka.
- **Log-based CDC** (Debezium reading the Postgres WAL): lower latency and no polling, but it needs a
  Kafka Connect worker (another JVM, ~512 MB+) and logical replication setup. Too heavy for 8 GB RAM,
  and more moving parts than one service with one event type needs.
- **Kafka transactions**: they only make Kafka writes atomic with each other, not with Postgres.

## Decision

- **Write side.** `AuthService.register` still raises the in-process `UserRegistered` event. A synchronous
  `@EventListener` (`DomainEventRecorder`) builds the envelope and inserts it into `outbox_events` **in the
  same transaction**. If the transaction rolls back, the event disappears with it. If the insert fails,
  registration fails. The insert is `Propagation.MANDATORY`, so raising an event outside a transaction
  throws instead of writing a stray row.
- **The envelope is built at write time**, including its `eventId`, and stored as `jsonb`. Every resend
  carries the same `eventId`, which is what consumers dedupe on (ADR 0013).
- **Relay.** `OutboxRelay` runs on Spring's scheduler every 500 ms. Each run happens in one transaction:
  1. `pg_try_advisory_xact_lock`: only one replica relays at a time; the others skip the run.
  2. Read up to 100 unpublished rows `ORDER BY id`. `id` is an identity sequence, so it follows insert order.
  3. Send them all, then wait for the acks in order.
  4. Mark the acknowledged prefix `published_at = now()`, and stop at the first failure. That row gets
     `attempts + 1` and `last_error`, and later rows wait for the next run, so they can't overtake it.
- **Backoff.** After a failed run (Kafka or Postgres down), the next run waits 0.5 s, then 1, 2, 4 … up to
  30 s. This keeps the log quiet and avoids hammering a dead broker. The producer fails fast:
  `max.block.ms` 10 s, `delivery.timeout.ms` 15 s, and the relay gives up waiting after 20 s.
- **Retention.** Published rows are kept for 7 days for debugging and manual replays, then deleted by an
  hourly job. Partial indexes keep the "pending" and "old published" lookups small.
- **Metrics.** `identity_outbox_pending` (gauge), `identity_outbox_published_total`, and
  `identity_outbox_failures_total`. A growing `pending` means events are committed but not reaching Kafka.

## Consequences

- **No lost events**: once the user row commits, its event reaches Kafka eventually. Verified in compose:
  Kafka stopped → register (201, 0.3 s) → the row stays pending with attempts counted and the relay backs
  off → Kafka started → published within the backoff window → welcome email sent.
- **At-least-once, not exactly-once.** A crash after Kafka acked but before the commit resends the row.
  A timed-out send that lands later also duplicates. Consumers must stay idempotent (they are: ADR 0013).
- **Latency**: commit to Kafka now takes up to one poll interval (measured 170–580 ms). A Postgres
  `LISTEN/NOTIFY` wake-up could remove that if it ever matters.
- **Ordering**: per key, insert order is kept. That's one relay at a time plus stop-at-first-failure.
  The cost is throughput: one replica sends at a time. `FOR UPDATE SKIP LOCKED` would let replicas
  share the work, but then the same key could be sent by two replicas in parallel. That's not worth it
  at this volume.
- A relay run holds a pooled DB connection while it waits for Kafka (at most ~20 s while Kafka is
  failing, then the backoff starts). The pool has 5 connections, and the relay uses at most one.
- An outbox row is only as good as the code that writes it: every new event type needs its listener in
  `DomainEventRecorder`, and its topic needs a `NewTopic` bean.
