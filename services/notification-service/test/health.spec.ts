import 'reflect-metadata';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import type { StartedMongoDBContainer } from '@testcontainers/mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { startMongo, testConfig } from './support/containers.js';

// Probes with MongoDB up and Kafka unreachable: the HTTP side must work without a broker.
describe('probes', () => {
  let mongo: StartedMongoDBContainer;
  let app: INestApplication;
  let baseUrl: string;

  beforeAll(async () => {
    const started = await startMongo();
    mongo = started.container;
    const moduleRef = await Test.createTestingModule({ imports: [AppModule.forRoot(testConfig({ mongoUri: started.uri }))] }).compile();
    app = moduleRef.createNestApplication({ logger: false });
    await app.listen(0);
    baseUrl = await app.getUrl();
  });

  afterAll(async () => {
    await app?.close();
    await mongo?.stop();
  });

  it('/health is UP without checking dependencies', async () => {
    const res = await fetch(`${baseUrl}/health`);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 'UP' });
  });

  it('/ready is 503 while Kafka is unreachable', async () => {
    const res = await fetch(`${baseUrl}/ready`);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ status: 'DOWN', components: { mongo: 'UP', kafka: 'DOWN' } });
  });

  it('/metrics serves Prometheus text', async () => {
    const res = await fetch(`${baseUrl}/metrics`);
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('text/plain');
    const body = await res.text();
    expect(body).toContain('process_resident_memory_bytes');
    expect(body).toContain('# TYPE notification_events_total counter');
  });

  it('/ready reports MongoDB DOWN when it is gone', async () => {
    await mongo.stop();
    const res = await fetch(`${baseUrl}/ready`);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ status: 'DOWN', components: { mongo: 'DOWN', kafka: 'DOWN' } });
  });
});
