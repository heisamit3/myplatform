import { Inject, Injectable, type OnApplicationShutdown } from '@nestjs/common';
import { createTransport } from 'nodemailer';
import type { AppConfig } from '../config.js';
import { APP_CONFIG } from '../tokens.js';
import type { Email } from './welcome-email.js';

// SMTP failure with a message that is safe to log: SMTP servers often echo the recipient address in
// their error text, so only the error code and SMTP response code are kept.
export class MailDeliveryError extends Error {
  override readonly name = 'MailDeliveryError';

  static from(err: unknown): MailDeliveryError {
    const e = err as { code?: unknown; responseCode?: unknown };
    const code = typeof e?.code === 'string' ? e.code : 'UNKNOWN';
    const response = typeof e?.responseCode === 'number' ? ` (SMTP ${e.responseCode})` : '';
    return new MailDeliveryError(`SMTP delivery failed: ${code}${response}`);
  }
}

@Injectable()
export class MailerService implements OnApplicationShutdown {
  private readonly transport;
  private readonly from: string;

  constructor(@Inject(APP_CONFIG) config: AppConfig) {
    this.from = config.mail.from;
    this.transport = createTransport({
      host: config.smtp.host,
      port: config.smtp.port,
      // Plain SMTP locally (Mailpit). A real provider would use port 465 (secure) or STARTTLS on 587.
      secure: false,
      // Reuse one connection instead of a TCP + SMTP handshake per email.
      pool: true,
      maxConnections: 1,
      // Fail within seconds when the server is down, so the consumer can back off and retry.
      connectionTimeout: 5_000,
      greetingTimeout: 5_000,
      socketTimeout: 10_000,
    });
  }

  /** messageId makes the email traceable to its event (and lets a mail server spot duplicates). */
  async send(email: Email, messageId: string): Promise<void> {
    try {
      await this.transport.sendMail({ from: this.from, messageId, ...email });
    } catch (err) {
      throw MailDeliveryError.from(err);
    }
  }

  onApplicationShutdown(): void {
    this.transport.close();
  }
}
