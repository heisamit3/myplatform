import { describe, expect, it } from 'vitest';
import { loadConfig } from './config.js';

describe('loadConfig', () => {
  const base = { MONGODB_URI: 'mongodb://u:p@localhost:27017/notification' };

  it('uses host-friendly defaults for optional values', () => {
    expect(loadConfig(base)).toEqual({
      port: 8082,
      mongoUri: base.MONGODB_URI,
      kafka: { brokers: 'localhost:9094', groupId: 'notification-service' },
      smtp: { host: 'localhost', port: 2525 },
      mail: { from: 'MyPlatform <no-reply@myplatform.local>', webUrl: 'http://localhost:5173' },
    });
  });

  it('reads overrides', () => {
    const config = loadConfig({ ...base, KAFKA_BOOTSTRAP_SERVERS: 'kafka:9092', SMTP_HOST: 'mailpit', SMTP_PORT: '1025' });
    expect(config.kafka.brokers).toBe('kafka:9092');
    expect(config.smtp).toEqual({ host: 'mailpit', port: 1025 });
  });

  it('fails fast without MONGODB_URI', () => {
    expect(() => loadConfig({})).toThrow('MONGODB_URI');
    expect(() => loadConfig({ MONGODB_URI: '  ' })).toThrow('MONGODB_URI');
  });

  it('rejects a non-numeric port', () => {
    expect(() => loadConfig({ ...base, PORT: 'abc' })).toThrow("PORT must be a positive integer, got 'abc'");
  });
});
