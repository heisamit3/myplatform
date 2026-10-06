import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { parseUserRegisteredV1 } from './user-registered.js';

// The producer's contract example: if identity-service's documented event stops parsing, this fails.
const example = readFileSync(
  new URL('../../../../contracts/events/examples/identity.user.registered.v1.json', import.meta.url),
  'utf8',
);

type Json = Record<string, any>;

function withChanges(change: (event: Json) => void): string {
  const event = JSON.parse(example) as Json;
  change(event);
  return JSON.stringify(event);
}

describe('parseUserRegisteredV1', () => {
  it('accepts the contract example', () => {
    expect(parseUserRegisteredV1(Buffer.from(example))).toEqual({
      ok: true,
      event: {
        eventId: '0199b9a8-6f3e-7c41-9d2a-5b8e1f0c3a77',
        occurredAt: '2026-10-06T13:30:00.123Z',
        orgId: null,
        payload: { userId: '0199b9a8-6f3a-7e10-8b6c-2d4f9a1e7c55', email: 'a@example.com', displayName: 'A' },
      },
    });
  });

  it('ignores unknown fields (tolerant reader, ADR 0010)', () => {
    const result = parseUserRegisteredV1(
      withChanges((e) => {
        e.traceId = 'abc';
        e.payload.locale = 'en';
      }),
    );
    expect(result.ok).toBe(true);
  });

  it.each([
    ['a tombstone', null, 'empty message (tombstone)'],
    ['non-JSON', 'not json', 'value is not JSON'],
    ['an array', '[]', 'value is not a JSON object'],
    ['a missing eventId', withChanges((e) => delete e.eventId), 'eventId is not a UUID'],
    ['another event type', withChanges((e) => (e.type = 'identity.user.deleted')), 'type/version is not identity.user.registered v1'],
    ['version 2', withChanges((e) => (e.version = 2)), 'type/version is not identity.user.registered v1'],
    ['a missing payload', withChanges((e) => delete e.payload), 'payload is not an object'],
    ['a missing email', withChanges((e) => delete e.payload.email), 'payload.email is not an email address'],
    ['a blank displayName', withChanges((e) => (e.payload.displayName = ' ')), 'payload.displayName is empty'],
  ])('rejects %s', (_name, value, reason) => {
    expect(parseUserRegisteredV1(value)).toEqual({ ok: false, reason });
  });

  it('lower-cases the eventId (dedupe key)', () => {
    const result = parseUserRegisteredV1(withChanges((e) => (e.eventId = e.eventId.toUpperCase())));
    expect(result.ok && result.event.eventId).toBe('0199b9a8-6f3e-7c41-9d2a-5b8e1f0c3a77');
  });

  it('never puts field values into the reason', () => {
    const result = parseUserRegisteredV1(withChanges((e) => (e.payload.email = 'secret-person')));
    expect(result).toEqual({ ok: false, reason: 'payload.email is not an email address' });
  });
});
