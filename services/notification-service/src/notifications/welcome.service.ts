import { Inject, Injectable } from '@nestjs/common';
import type { AppConfig } from '../config.js';
import type { UserRegisteredV1 } from '../events/user-registered.js';
import { APP_CONFIG } from '../tokens.js';
import { MailerService } from './mailer.service.js';
import { NotificationRepository } from './notification.repository.js';
import { welcomeEmail } from './welcome-email.js';

export type Outcome = 'sent' | 'duplicate';

const TEMPLATE = 'welcome';

// Message-ID derived from the event, so the same event always produces the same Message-ID.
export function messageIdFor(eventId: string, template: string): string {
  return `<${eventId}.${template}@notification.myplatform.local>`;
}

@Injectable()
export class WelcomeService {
  constructor(
    private readonly notifications: NotificationRepository,
    private readonly mailer: MailerService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  /**
   * Sends the welcome email once per event. Errors propagate so the consumer retries the message.
   *
   * At-least-once: if the process dies between sending and markSent, the redelivered event finds the
   * notification still 'pending' and sends again. Recording first and sending second means we never
   * lose an email silently; the rare duplicate carries the same Message-ID.
   */
  async handle(event: UserRegisteredV1): Promise<Outcome> {
    const notification = await this.notifications.claim({
      eventId: event.eventId,
      eventType: 'identity.user.registered',
      template: TEMPLATE,
      channel: 'email',
      orgId: event.orgId,
      userId: event.payload.userId,
      to: event.payload.email,
      occurredAt: new Date(event.occurredAt),
    });
    if (notification.status === 'sent') {
      return 'duplicate';
    }

    const messageId = messageIdFor(event.eventId, TEMPLATE);
    const email = welcomeEmail({
      to: notification.to,
      displayName: event.payload.displayName,
      webUrl: this.config.mail.webUrl,
    });
    try {
      await this.mailer.send(email, messageId);
    } catch (err) {
      // Best effort: the history shows the failed attempt. The send error is what matters to the caller.
      await this.notifications
        .recordFailure(notification._id, err instanceof Error ? err.message : String(err))
        .catch(() => undefined);
      throw err;
    }
    await this.notifications.markSent(notification._id, messageId);
    return 'sent';
  }
}
