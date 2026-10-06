-- Transactional outbox (ADR 0014). A domain change and the event describing it are written in the same
-- transaction; OutboxRelay sends unpublished rows to Kafka afterwards and marks them published.
CREATE TABLE outbox_events (
    -- Relay order. A sequence, not the UUID: eventIds are random, and the relay must send in insert order.
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     uuid        NOT NULL UNIQUE,
    topic        text        NOT NULL,
    -- Kafka record key: the entity the event is about, so its events share a partition and stay in order.
    record_key   text        NOT NULL,
    -- The full envelope, exactly as consumers receive it. jsonb so it can be inspected with SQL when debugging.
    envelope     jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts     integer     NOT NULL DEFAULT 0,
    -- Exception class and message of the last failed send; never the payload (it can hold personal data).
    last_error   text
);
-- The relay's query: "oldest unpublished first". Partial, so it stays small however many rows are kept.
CREATE INDEX outbox_events_pending_idx ON outbox_events (id) WHERE published_at IS NULL;
-- Cleanup of published rows older than the retention.
CREATE INDEX outbox_events_published_at_idx ON outbox_events (published_at) WHERE published_at IS NOT NULL;
