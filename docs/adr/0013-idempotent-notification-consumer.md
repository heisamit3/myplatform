# 0013: notification-service consumes at-least-once and dedupes in MongoDB

Date: 2026-10-06 · Status: accepted

## Context

notification-service turns `identity.user.registered.v1` into a welcome email. Kafka delivers
at-least-once: a consumer restart, a rebalance or a future outbox relay (ADR 0011) can hand us the
same event twice. Sending an email is a side effect that can't be rolled back. CLAUDE.md requires
idempotent consumers that dedupe on `eventId`.

## Decision

- **History doubles as the dedupe table.** Collection `notifications` has one document per event and template,
  with a **unique index on `{eventId, template}`**. The handler upserts with `$setOnInsert`, so a redelivery
  finds the existing document instead of creating a second one (an `E11000` race also ends with the existing document).
- **Record first, then send:** document `pending` → SMTP send → `sent`. If the document is already `sent`, the event is a
  duplicate and is skipped. If it's still `pending` (we crashed or SMTP failed before), the email is sent again.
  This is at-least-once email: we never drop one silently, and the rare duplicate has the same
  **Message-ID `<eventId.template@notification.myplatform.local>`**, which traces it back to its event.
- **Offsets:** auto-commit of *stored* offsets. The client stores an offset only after `eachMessage` returns,
  so a message whose handler threw is never committed. On shutdown, `disconnect()` commits and leaves the group.
- **Errors**
  - Unparseable or wrong-type message: logged (field name only, no values), counted as `invalid`,
    and **skipped** so a poison message can't block the partition. Later this can go to a dead-letter topic.
  - MongoDB or SMTP failure: counted as `failed`, then a **backoff of 1 s → 30 s**, then rethrow. The client
    seeks back and redelivers the same message, so the partition waits until the dependency is back
    (order per key is kept).
- **Tolerant reader** (ADR 0010): only the fields we use are checked. A unit test parses the producer's contract
  example from `contracts/events/examples/`.
- **No PII in logs:** SMTP errors are reduced to their codes (`MailDeliveryError`), because server texts often
  echo the recipient address. MongoDB stores `to`, because the history needs it.
- `/ready` = MongoDB ping + Kafka consumer connected. The consumer starts in the background with retries,
  so `/health` and `/metrics` answer even while Kafka is down.

## Consequences

- A crash between the SMTP send and `markSent` sends a second email. Exactly-once email isn't possible
  without the provider's help (some providers take an idempotency key).
- One stuck message (say, SMTP permanently rejecting a recipient) stalls its partition with retries.
  Fine for one event type locally. With more traffic it needs a retry limit plus a dead-letter topic,
  and permanent SMTP 5xx errors should be told apart from transient 4xx ones.
- `notification_events_total{topic,outcome}` makes these outcomes visible in Prometheus (Phase 5).
- A new consumer group starts at the earliest retained offset (`auto.offset.reset=earliest`), so it would
  email every user still in topic retention. That's expected for a first deploy. Retention is a privacy setting
  anyway (ADR 0010).
