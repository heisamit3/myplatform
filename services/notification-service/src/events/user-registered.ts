// identity.user.registered.v1 (contracts/events/identity.user.registered.v1.schema.json).
// Tolerant reader (ADR 0010): check only the fields this service uses, ignore everything else.
// Rejection reasons name the field, never its value: the payload holds an email address.

export const USER_REGISTERED_V1_TOPIC = 'identity.user.registered.v1';

export interface UserRegisteredV1 {
  eventId: string;
  occurredAt: string;
  orgId: string | null;
  payload: {
    userId: string;
    email: string;
    displayName: string;
  };
}

export type ParseResult = { ok: true; event: UserRegisteredV1 } | { ok: false; reason: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function parseUserRegisteredV1(value: Buffer | string | null): ParseResult {
  if (value === null) {
    return fail('empty message (tombstone)');
  }
  let data: unknown;
  try {
    data = JSON.parse(value.toString());
  } catch {
    return fail('value is not JSON');
  }
  if (!isObject(data)) {
    return fail('value is not a JSON object');
  }
  if (typeof data.eventId !== 'string' || !UUID.test(data.eventId)) {
    return fail('eventId is not a UUID');
  }
  if (data.type !== 'identity.user.registered' || data.version !== 1) {
    return fail('type/version is not identity.user.registered v1');
  }
  if (typeof data.occurredAt !== 'string') {
    return fail('occurredAt is missing');
  }
  const orgId = data.orgId ?? null;
  if (orgId !== null && typeof orgId !== 'string') {
    return fail('orgId is not a string or null');
  }
  const payload = data.payload;
  if (!isObject(payload)) {
    return fail('payload is not an object');
  }
  if (typeof payload.userId !== 'string' || !UUID.test(payload.userId)) {
    return fail('payload.userId is not a UUID');
  }
  if (typeof payload.email !== 'string' || !payload.email.includes('@')) {
    return fail('payload.email is not an email address');
  }
  if (typeof payload.displayName !== 'string' || payload.displayName.trim() === '') {
    return fail('payload.displayName is empty');
  }
  return {
    ok: true,
    event: {
      // Lower-case so "same UUID, different case" can't slip past the unique index.
      eventId: data.eventId.toLowerCase(),
      occurredAt: data.occurredAt,
      orgId,
      payload: { userId: payload.userId, email: payload.email, displayName: payload.displayName },
    },
  };
}

function fail(reason: string): ParseResult {
  return { ok: false, reason };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
