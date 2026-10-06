import 'reflect-metadata';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { MongoDBContainer, type StartedMongoDBContainer } from '@testcontainers/mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';

// Same image as infra/compose/compose.yaml.
const MONGO_IMAGE = 'mongo:8.0.32';

describe('probes', () => {
  let mongo: StartedMongoDBContainer;
  let app: INestApplication;
  let baseUrl: string;

  beforeAll(async () => {
    mongo = await new MongoDBContainer(MONGO_IMAGE).start();
    const moduleRef = await Test.createTestingModule({
      imports: [AppModule.forRoot({ port: 0, mongoUri: `${mongo.getConnectionString()}/notification?directConnection=true` })],
    }).compile();
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

  it('/ready reports MongoDB', async () => {
    const res = await fetch(`${baseUrl}/ready`);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 'UP', components: { mongo: 'UP' } });
  });

  it('/metrics serves Prometheus text', async () => {
    const res = await fetch(`${baseUrl}/metrics`);
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('text/plain');
    expect(await res.text()).toContain('process_resident_memory_bytes');
  });

  it('/ready is 503 when MongoDB is gone', async () => {
    await mongo.stop();
    const res = await fetch(`${baseUrl}/ready`);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ status: 'DOWN', components: { mongo: 'DOWN' } });
  });
});
