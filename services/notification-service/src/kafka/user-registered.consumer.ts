import { KafkaJS } from '@confluentinc/kafka-javascript';
import {
  type BeforeApplicationShutdown,
  Inject,
  Injectable,
  Logger,
  type OnApplicationBootstrap,
} from '@nestjs/common';
import { setTimeout as sleep } from 'node:timers/promises';
import type { AppConfig } from '../config.js';
import { parseUserRegisteredV1, USER_REGISTERED_V1_TOPIC } from '../events/user-registered.js';
import { MetricsService } from '../metrics/metrics.service.js';
import { WelcomeService } from '../notifications/welcome.service.js';
import { APP_CONFIG } from '../tokens.js';
import { nestKafkaLogger } from './kafka-logger.js';

const MAX_BACKOFF_MS = 30_000;

/**
 * Consumes identity.user.registered.v1 (ADR 0012, ADR 0013).
 *
 * Delivery is at-least-once: offsets are committed only for messages whose handler returned.
 * When the handler throws, the client seeks back and redelivers the same message, so a failing
 * message blocks its partition until it succeeds (order per user is kept). Duplicates are expected
 * and are absorbed by WelcomeService (dedupe on eventId).
 */
@Injectable()
export class UserRegisteredConsumer implements OnApplicationBootstrap, BeforeApplicationShutdown {
  private readonly logger = new Logger(UserRegisteredConsumer.name);
  private readonly consumer: KafkaJS.Consumer;
  private readonly stopping = new AbortController();
  private connected = false;
  private consecutiveFailures = 0;

  constructor(
    @Inject(APP_CONFIG) config: AppConfig,
    private readonly welcome: WelcomeService,
    private readonly metrics: MetricsService,
  ) {
    // librdkafka property names (same keys as the Python/Go clients), with the KafkaJS-style run() API.
    // The kafkaJS block holds only what has no librdkafka key: the logger (and groupId, which its type requires).
    this.consumer = new KafkaJS.Kafka({}).consumer({
      kafkaJS: { groupId: config.kafka.groupId, logger: nestKafkaLogger('KafkaConsumer') },
      'bootstrap.servers': config.kafka.brokers,
      'client.id': 'notification-service',
      // A brand-new group starts at the oldest retained event instead of only new ones.
      'auto.offset.reset': 'earliest',
      // Typos must not create topics (the broker has auto-create off too, ADR 0009).
      'allow.auto.create.topics': false,
      // Memory: librdkafka prefetches into a local queue, up to 64 MB by default. Events are tiny,
      // so 2 MB of prefetch is plenty and keeps the process inside its 192M container limit.
      'queued.max.messages.kbytes': 2048,
      'fetch.max.bytes': 1_048_576,
      // Notice a topic created after we subscribed within 30 s (default 5 min).
      'topic.metadata.refresh.interval.ms': 30_000,
    });
  }

  /** Readiness: connected to the cluster. Partitions may still be (re)assigned. */
  isConnected(): boolean {
    return this.connected;
  }

  // Not awaited: the HTTP server (probes, metrics) starts even while Kafka is unreachable.
  onApplicationBootstrap(): void {
    void this.start();
  }

  private async start(): Promise<void> {
    for (let attempt = 1; !this.stopping.signal.aborted; attempt++) {
      try {
        await this.consumer.connect();
        this.connected = true;
        await this.consumer.subscribe({ topics: [USER_REGISTERED_V1_TOPIC] });
        await this.consumer.run({ eachMessage: (payload) => this.onMessage(payload) });
        this.logger.log(`Consuming ${USER_REGISTERED_V1_TOPIC}`);
        return;
      } catch (err) {
        this.connected = false;
        const delay = backoff(attempt);
        this.logger.error(`Kafka consumer did not start (attempt ${attempt}): ${describe(err)}; retrying in ${delay} ms`);
        await this.consumer.disconnect().catch(() => undefined);
        await sleep(delay, undefined, { signal: this.stopping.signal }).catch(() => undefined);
      }
    }
  }

  private async onMessage({ topic, partition, message }: KafkaJS.EachMessagePayload): Promise<void> {
    const where = `${topic}[${partition}]@${message.offset}`;
    const parsed = parseUserRegisteredV1(message.value);
    if (!parsed.ok) {
      // A message that can never be handled must not block the partition: log it and move on.
      // (A dead-letter topic would keep it for inspection; not needed yet.)
      this.metrics.events.inc({ topic, outcome: 'invalid' });
      this.logger.warn(`Skipped ${where}: ${parsed.reason}`);
      return;
    }

    const { eventId } = parsed.event;
    try {
      const outcome = await this.welcome.handle(parsed.event);
      this.consecutiveFailures = 0;
      this.metrics.events.inc({ topic, outcome });
      this.logger.log(`Event ${eventId} ${where}: ${outcome === 'sent' ? 'welcome email sent' : 'duplicate, ignored'}`);
    } catch (err) {
      // Transient (SMTP or MongoDB down): wait, then throw so the client redelivers this message.
      // The client itself retries immediately, which would spin while a dependency is down.
      this.consecutiveFailures++;
      this.metrics.events.inc({ topic, outcome: 'failed' });
      const delay = backoff(this.consecutiveFailures);
      this.logger.error(`Event ${eventId} ${where} failed: ${describe(err)}; retrying in ${delay} ms`);
      await sleep(delay, undefined, { signal: this.stopping.signal }).catch(() => undefined);
      throw err;
    }
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.stopping.abort();
    // Commits the offsets of messages handled so far and leaves the group, so a restart (or another
    // instance) takes over the partitions right away instead of after the session timeout.
    await this.consumer.disconnect().catch((err: unknown) => this.logger.warn(`Kafka disconnect: ${describe(err)}`));
    this.connected = false;
  }
}

// 1 s, 2 s, 4 s ... capped at 30 s.
function backoff(attempt: number): number {
  return Math.min(1_000 * 2 ** (attempt - 1), MAX_BACKOFF_MS);
}

function describe(err: unknown): string {
  return err instanceof Error ? `${err.name}: ${err.message}` : String(err);
}
