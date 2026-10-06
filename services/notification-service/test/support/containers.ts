import { MongoDBContainer, type StartedMongoDBContainer } from '@testcontainers/mongodb';
import { createServer, type AddressInfo } from 'node:net';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import type { AppConfig } from '../../src/config.js';

// Same images as infra/compose/compose.yaml, so tests run against what we deploy.
export const MONGO_IMAGE = 'mongo:8.0.32';
export const KAFKA_IMAGE = 'apache/kafka:4.3.1';
export const MAILPIT_IMAGE = 'axllent/mailpit:v1.31.4';

export async function startMongo(): Promise<{ container: StartedMongoDBContainer; uri: string }> {
  const container = await new MongoDBContainer(MONGO_IMAGE).start();
  // The module starts a one-node replica set with an internal hostname; connect to the node directly.
  return { container, uri: `${container.getConnectionString()}/notification?directConnection=true` };
}

/**
 * Single KRaft node, configured like compose. @testcontainers/kafka only supports Confluent's cp-kafka image,
 * so this uses a GenericContainer. Kafka hands clients the *advertised* address after the first connect, so
 * the host port must be known before the broker starts: pick a free port and map it 1:1.
 */
export async function startKafka(): Promise<{ container: StartedTestContainer; brokers: string }> {
  const port = await freePort();
  const container = await new GenericContainer(KAFKA_IMAGE)
    .withExposedPorts({ container: 9094, host: port })
    .withEnvironment({
      KAFKA_NODE_ID: '1',
      KAFKA_PROCESS_ROLES: 'broker,controller',
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@localhost:9093',
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER',
      KAFKA_LISTENERS: 'PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094',
      KAFKA_ADVERTISED_LISTENERS: `PLAINTEXT://localhost:9092,EXTERNAL://localhost:${port}`,
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT',
      KAFKA_INTER_BROKER_LISTENER_NAME: 'PLAINTEXT',
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1',
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: '1',
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: '1',
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: '0',
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false',
      KAFKA_HEAP_OPTS: '-Xms128m -Xmx384m',
    })
    .withWaitStrategy(Wait.forLogMessage(/Kafka Server started/))
    .withStartupTimeout(120_000)
    .start();
  return { container, brokers: `${container.getHost()}:${port}` };
}

export async function startMailpit(): Promise<{ container: StartedTestContainer; smtpPort: number; apiUrl: string }> {
  const container = await new GenericContainer(MAILPIT_IMAGE)
    .withExposedPorts(1025, 8025)
    .withWaitStrategy(Wait.forHttp('/livez', 8025))
    .start();
  return {
    container,
    smtpPort: container.getMappedPort(1025),
    apiUrl: `http://${container.getHost()}:${container.getMappedPort(8025)}`,
  };
}

export function testConfig(overrides: { mongoUri: string; brokers?: string; smtpPort?: number }): AppConfig {
  return {
    port: 0,
    mongoUri: overrides.mongoUri,
    // Port 1 refuses connections: "Kafka unreachable" unless a broker is passed in.
    kafka: { brokers: overrides.brokers ?? '127.0.0.1:1', groupId: 'notification-service-test' },
    smtp: { host: 'localhost', port: overrides.smtpPort ?? 1 },
    mail: { from: 'MyPlatform <no-reply@myplatform.local>', webUrl: 'http://localhost:5173' },
  };
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, () => {
      const { port } = server.address() as AddressInfo;
      server.close(() => resolve(port));
    });
  });
}

/** Polls until check() returns a value other than undefined, or fails after timeoutMs. */
export async function eventually<T>(check: () => Promise<T | undefined>, timeoutMs = 60_000, what = 'condition'): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const result = await check();
    if (result !== undefined) {
      return result;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
}
