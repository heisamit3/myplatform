# 0012: Node Kafka client: `@confluentinc/kafka-javascript`

Date: 2026-10-06 · Status: accepted

## Context

notification-service (NestJS) consumes Kafka events. CLAUDE.md requires a maintained client.
State on 2026-10-06 (npm + GitHub):

| Client | Latest release | Last commit | Notes |
|---|---|---|---|
| `kafkajs` | 2.2.4, Feb 2023 | May 2024 | ~420 open issues; effectively unmaintained |
| `node-rdkafka` | 3.6.1, Dec 2025 | Dec 2025 | Native librdkafka binding; superseded by Confluent's client |
| `@confluentinc/kafka-javascript` | 1.10.1, Sep 2026 | same day | Official Confluent client, librdkafka 2.15 |
| `@platformatic/kafka` | 2.13.0, Oct 2026 | Oct 2026 | Pure TypeScript, very active, young, one main maintainer |

NestJS's built-in Kafka transport (`@nestjs/microservices`) is built on `kafkajs`.

## Decision

- Use **`@confluentinc/kafka-javascript`** through its **KafkaJS-compatible API** (`KafkaJS.Kafka`).
  - It's vendor-supported and wraps **librdkafka**, the same C engine behind Confluent's Python, Go and
    .NET clients. The later Python ai-service (`confluent-kafka`) gets the same config keys and the same behavior.
  - The KafkaJS-style API (`consumer.run({ eachMessage })`) means most KafkaJS docs and examples still apply.
- **Don't use the `@nestjs/microservices` Kafka transport** (it pulls in `kafkajs`). The consumer is a plain
  Nest provider that starts on `onApplicationBootstrap` and disconnects on shutdown.
- librdkafka buffers up to 64 MB per consumer by default (`queued.max.messages.kbytes`). We lower the
  fetch buffers so the service fits its 192M container limit.

## Consequences

- It's a native addon: `npm ci` downloads a prebuilt binary (win32, linux glibc and musl; Node 24 ABI is included).
  That keeps the Dockerfile simple, but the binary in the image must match the base image's libc.
  The build and runtime stages use the same Node base image.
- npm's install-script allowlist must permit this package (`allowScripts` in `package.json`).
- If `eachMessage` throws, the client seeks back and redelivers the same message. It doesn't back off,
  so the handler adds its own delay before rethrowing a transient error.
- Alternative if the native addon causes trouble (e.g. a new Node major without prebuilt binaries): `@platformatic/kafka`.
  The consumer sits behind one small class, so a switch would stay contained.
