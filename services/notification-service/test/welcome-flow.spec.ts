import 'reflect-metadata';
import { KafkaJS } from '@confluentinc/kafka-javascript';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import type { StartedMongoDBContainer } from '@testcontainers/mongodb';
import { randomUUID } from 'node:crypto';
import type { StartedTestContainer } from 'testcontainers';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { USER_REGISTERED_V1_TOPIC } from '../src/events/user-registered.js';
import { NotificationRepository } from '../src/notifications/notification.repository.js';
import { eventually, startKafka, startMailpit, startMongo, testConfig } from './support/containers.js';

// End to end inside the service: Kafka event -> MongoDB history -> email in Mailpit.
describe('welcome email flow', () => {
  let kafka: StartedTestContainer;
  let mongo: StartedMongoDBContainer;
  let mailpit: StartedTestContainer;
  let mailpitApi: string;
  let producer: KafkaJS.Producer;
  let app: INestApplication;
  let baseUrl: string;

  beforeAll(async () => {
    const [k, m, mp] = await Promise.all([startKafka(), startMongo(), startMailpit()]);
    kafka = k.container;
    mongo = m.container;
    mailpit = mp.container;
    mailpitApi = mp.apiUrl;

    // identity-service declares this topic in real life; the broker has auto-create off.
    const client = new KafkaJS.Kafka({});
    const admin = client.admin({ 'bootstrap.servers': k.brokers });
    await admin.connect();
    await admin.createTopics({ topics: [{ topic: USER_REGISTERED_V1_TOPIC, numPartitions: 3, replicationFactor: 1 }] });
    await admin.disconnect();
    producer = client.producer({ 'bootstrap.servers': k.brokers });
    await producer.connect();

    const moduleRef = await Test.createTestingModule({
      imports: [AppModule.forRoot(testConfig({ mongoUri: m.uri, brokers: k.brokers, smtpPort: mp.smtpPort }))],
    }).compile();
    app = moduleRef.createNestApplication({ logger: ['error', 'warn'] });
    await app.listen(0);
    baseUrl = await app.getUrl();
  });

  afterAll(async () => {
    await app?.close();
    await producer?.disconnect();
    await Promise.all([kafka?.stop(), mongo?.stop(), mailpit?.stop()]);
  });

  it('/ready is UP with Kafka and MongoDB', async () => {
    const body = await eventually(async () => {
      const res = await fetch(`${baseUrl}/ready`);
      return res.status === 200 ? res.json() : undefined;
    }, 30_000, '/ready');
    expect(body).toEqual({ status: 'UP', components: { mongo: 'UP', kafka: 'UP' } });
  });

  it('sends one welcome email per event and ignores redeliveries and garbage', async () => {
    const ada = registered('ada@example.com', 'Ada');
    const bob = registered('bob@example.com', 'Bob <b>');
    await producer.send({
      topic: USER_REGISTERED_V1_TOPIC,
      messages: [
        { key: ada.payload.userId, value: JSON.stringify(ada) },
        // Redelivery of the same event (e.g. a producer/outbox retry): same eventId.
        { key: ada.payload.userId, value: JSON.stringify(ada) },
        // Poison message: must be skipped without blocking the partition.
        { key: ada.payload.userId, value: 'not json' },
        { key: bob.payload.userId, value: JSON.stringify(bob) },
      ],
    });

    // Wait until all four messages were handled (the metrics count each outcome).
    await eventually(async () => {
      const metrics = await (await fetch(`${baseUrl}/metrics`)).text();
      const done =
        counter(metrics, 'sent') === 2 && counter(metrics, 'duplicate') === 1 && counter(metrics, 'invalid') === 1;
      return done ? true : undefined;
    }, 60_000, 'all four messages to be handled');

    const mail = (await (await fetch(`${mailpitApi}/api/v1/messages`)).json()) as MailpitList;
    expect(mail.total).toBe(2);
    const byRecipient = Object.fromEntries(mail.messages.map((msg) => [msg.To[0]!.Address, msg]));
    expect(byRecipient['ada@example.com']).toMatchObject({
      Subject: 'Welcome to MyPlatform',
      MessageID: `${ada.eventId}.welcome@notification.myplatform.local`,
    });
    expect(byRecipient['bob@example.com']?.MessageID).toBe(`${bob.eventId}.welcome@notification.myplatform.local`);

    const repo = app.get(NotificationRepository);
    expect(await repo.findByEventId(ada.eventId)).toMatchObject([
      { template: 'welcome', channel: 'email', status: 'sent', attempts: 1, to: 'ada@example.com', orgId: null },
    ]);
    expect(await repo.findByEventId(bob.eventId)).toHaveLength(1);
  });
});

interface MailpitList {
  total: number;
  messages: { MessageID: string; Subject: string; To: { Address: string }[] }[];
}

function registered(email: string, displayName: string) {
  return {
    eventId: randomUUID(),
    type: 'identity.user.registered',
    version: 1,
    occurredAt: new Date().toISOString(),
    orgId: null,
    payload: { userId: randomUUID(), email, displayName },
  };
}

function counter(metrics: string, outcome: string): number {
  const line = metrics
    .split('\n')
    .find((l) => l.startsWith(`notification_events_total{topic="${USER_REGISTERED_V1_TOPIC}",outcome="${outcome}"}`));
  return line ? Number(line.split(' ').pop()) : 0;
}
