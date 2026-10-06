import { describe, expect, it } from 'vitest';
import { loadConfig } from './config.js';

describe('loadConfig', () => {
  const base = { MONGODB_URI: 'mongodb://u:p@localhost:27017/notification' };

  it('uses defaults for optional values', () => {
    expect(loadConfig(base)).toEqual({ port: 8082, mongoUri: base.MONGODB_URI });
  });

  it('fails fast without MONGODB_URI', () => {
    expect(() => loadConfig({})).toThrow('MONGODB_URI');
    expect(() => loadConfig({ MONGODB_URI: '  ' })).toThrow('MONGODB_URI');
  });

  it('rejects a non-numeric port', () => {
    expect(() => loadConfig({ ...base, PORT: 'abc' })).toThrow("PORT must be a positive integer, got 'abc'");
  });
});
