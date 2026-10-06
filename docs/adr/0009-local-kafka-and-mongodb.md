# 0009: Local Kafka (official image, KRaft) and MongoDB in the `events` profile

Date: 2026-10-06 · Status: accepted

## Context

Phase 3 needs a Kafka broker and MongoDB on a machine where about 3.2 GB is usable for containers.
Kafka 4.x has removed ZooKeeper, so KRaft is the only mode. The common `bitnami/kafka` image stopped
receiving free updates in 2025, so we picked another image.

## Decision

- **Kafka: `apache/kafka`**, the image published by the Apache Kafka project, pinned to 4.3.1.
  One node is both **broker and controller** (`KAFKA_PROCESS_ROLES=broker,controller`), with a fixed
  `CLUSTER_ID` so the data volume survives `down`/`up`.
- **Three listeners:** `PLAINTEXT` `kafka:9092` for containers, `EXTERNAL` `localhost:9094` for tools on the
  host, and `CONTROLLER` `9093` for the KRaft quorum. A client connects to a bootstrap address, then
  reconnects to whatever the broker *advertises*, which is why each network needs its own listener.
- **`auto.create.topics.enable=false`.** The producing service declares its topics (a `NewTopic` bean in
  Spring). A misspelled topic name in a consumer then shows up as an error instead of a silent new topic.
- Heap `-Xms256m -Xmx512m` in a 768M limit. The healthcheck is `nc -z`, because the Kafka CLI healthcheck
  starts a second JVM inside the same memory limit.
- **MongoDB: `mongo:8.0.32`** (8.0 is the long-term-support line), WiredTiger cache capped at 0.25 GB,
  384M limit. A root user comes from `.env`, and an init script creates one database plus one `readWrite`
  user per service (`notification`), the same pattern as Postgres.
- Both are in the `events` profile and bind to 127.0.0.1 only.

## Consequences

- Idle after start: Kafka ~390 MiB, MongoDB ~200 MiB. `core` + `events` together need about 2.3 GB of
  limits, which fits the Docker VM but leaves Windows tight on 8 GB.
- Single broker: replication factor 1, so there is no fault tolerance. That's fine locally. EKS (Phase 7) still
  runs in-cluster Kafka (no MSK, for cost).
- No TLS/SASL locally. Kafka auth is a later upgrade if the AWS environment needs it.
