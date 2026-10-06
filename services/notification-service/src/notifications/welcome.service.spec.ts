import { ObjectId } from 'mongodb';
import { beforeEach, describe, expect, it } from 'vitest';
import type { AppConfig } from '../config.js';
import type { UserRegisteredV1 } from '../events/user-registered.js';
import { MailDeliveryError, type MailerService } from './mailer.service.js';
import type { NewNotification, NotificationDoc, NotificationRepository } from './notification.repository.js';
import type { Email } from './welcome-email.js';
import { messageIdFor, WelcomeService } from './welcome.service.js';

// In-memory stand-ins with the same semantics as the Mongo repository (unique on eventId + template).
class FakeRepository {
  readonly docs: NotificationDoc[] = [];

  async claim(n: NewNotification): Promise<NotificationDoc> {
    const existing = this.docs.find((d) => d.eventId === n.eventId && d.template === n.template);
    if (existing) {
      return { ...existing };
    }
    const doc: NotificationDoc = { ...n, _id: new ObjectId(), status: 'pending', attempts: 0, createdAt: new Date() };
    this.docs.push(doc);
    return { ...doc };
  }

  async markSent(id: ObjectId, messageId: string): Promise<void> {
    Object.assign(this.byId(id), { status: 'sent', messageId, attempts: this.byId(id).attempts + 1 });
  }

  async recordFailure(id: ObjectId, error: string): Promise<void> {
    Object.assign(this.byId(id), { lastError: error, attempts: this.byId(id).attempts + 1 });
  }

  private byId(id: ObjectId): NotificationDoc {
    const doc = this.docs.find((d) => d._id.equals(id));
    if (!doc) throw new Error('not found');
    return doc;
  }
}

class FakeMailer {
  readonly sent: { email: Email; messageId: string }[] = [];
  failNext = 0;

  async send(email: Email, messageId: string): Promise<void> {
    if (this.failNext > 0) {
      this.failNext--;
      throw new MailDeliveryError('SMTP delivery failed: ECONNECTION');
    }
    this.sent.push({ email, messageId });
  }
}

const config = { mail: { webUrl: 'http://localhost:5173' } } as AppConfig;

const event: UserRegisteredV1 = {
  eventId: '0199b9a8-6f3e-7c41-9d2a-5b8e1f0c3a77',
  occurredAt: '2026-10-06T13:30:00.123Z',
  orgId: null,
  payload: { userId: '0199b9a8-6f3a-7e10-8b6c-2d4f9a1e7c55', email: 'a@example.com', displayName: 'Ada' },
};

describe('WelcomeService', () => {
  let repo: FakeRepository;
  let mailer: FakeMailer;
  let service: WelcomeService;

  beforeEach(() => {
    repo = new FakeRepository();
    mailer = new FakeMailer();
    service = new WelcomeService(repo as unknown as NotificationRepository, mailer as unknown as MailerService, config);
  });

  it('sends the welcome email and records it as sent', async () => {
    expect(await service.handle(event)).toBe('sent');

    expect(mailer.sent).toHaveLength(1);
    expect(mailer.sent[0]!.email.to).toBe('a@example.com');
    expect(mailer.sent[0]!.messageId).toBe(messageIdFor(event.eventId, 'welcome'));
    expect(repo.docs).toMatchObject([
      { eventId: event.eventId, template: 'welcome', userId: event.payload.userId, orgId: null, status: 'sent', attempts: 1 },
    ]);
  });

  it('ignores a redelivered event (same eventId)', async () => {
    await service.handle(event);
    expect(await service.handle(event)).toBe('duplicate');
    expect(await service.handle({ ...event })).toBe('duplicate');

    expect(mailer.sent).toHaveLength(1);
    expect(repo.docs).toHaveLength(1);
  });

  it('treats a different eventId as a new notification', async () => {
    await service.handle(event);
    await service.handle({ ...event, eventId: '0199b9a8-6f3e-7c41-9d2a-5b8e1f0c3a78' });
    expect(mailer.sent).toHaveLength(2);
  });

  it('records a failed send and rethrows, then sends on redelivery', async () => {
    mailer.failNext = 1;
    await expect(service.handle(event)).rejects.toThrow('SMTP delivery failed: ECONNECTION');
    expect(repo.docs[0]).toMatchObject({ status: 'pending', attempts: 1, lastError: 'SMTP delivery failed: ECONNECTION' });

    expect(await service.handle(event)).toBe('sent');
    expect(mailer.sent).toHaveLength(1);
    expect(repo.docs[0]).toMatchObject({ status: 'sent', attempts: 2 });
  });

  it('resends when a previous attempt crashed before markSent (still pending)', async () => {
    await repo.claim({
      eventId: event.eventId,
      eventType: 'identity.user.registered',
      template: 'welcome',
      channel: 'email',
      orgId: null,
      userId: event.payload.userId,
      to: event.payload.email,
      occurredAt: new Date(event.occurredAt),
    });
    expect(await service.handle(event)).toBe('sent');
    expect(mailer.sent).toHaveLength(1);
  });
});

describe('MailDeliveryError.from', () => {
  it('keeps codes and drops the server text (it can contain the address)', () => {
    const smtpError = Object.assign(new Error('550 5.1.1 <a@example.com>: Recipient address rejected'), {
      code: 'EENVELOPE',
      responseCode: 550,
    });
    const err = MailDeliveryError.from(smtpError);
    expect(err.message).toBe('SMTP delivery failed: EENVELOPE (SMTP 550)');
    expect(err.message).not.toContain('example.com');
  });
});
