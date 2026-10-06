import { Injectable, type OnModuleInit } from '@nestjs/common';
import { type Collection, MongoServerError, type ObjectId } from 'mongodb';
import { MongoService } from '../mongo/mongo.service.js';

// Notification history: one document per (event, template). The unique index on that pair is what makes
// event handling idempotent: a redelivered event finds its existing document instead of creating a second one.

export type NotificationStatus = 'pending' | 'sent';

export interface NotificationDoc {
  _id: ObjectId;
  eventId: string;
  eventType: string;
  template: string;
  channel: 'email';
  // Tenant the notification belongs to; null for user-level events (CLAUDE.md: tenant data carries the org).
  orgId: string | null;
  userId: string;
  to: string;
  status: NotificationStatus;
  attempts: number;
  lastError?: string;
  occurredAt: Date;
  createdAt: Date;
  sentAt?: Date;
  messageId?: string;
}

export type NewNotification = Pick<
  NotificationDoc,
  'eventId' | 'eventType' | 'template' | 'channel' | 'orgId' | 'userId' | 'to' | 'occurredAt'
>;

const DUPLICATE_KEY = 11000;

@Injectable()
export class NotificationRepository implements OnModuleInit {
  private readonly collection: Collection<NotificationDoc>;

  constructor(mongo: MongoService) {
    this.collection = mongo.db.collection<NotificationDoc>('notifications');
  }

  // createIndexes is a no-op when the indexes already exist, so it is safe on every start.
  async onModuleInit(): Promise<void> {
    await this.collection.createIndexes([
      { key: { eventId: 1, template: 1 }, name: 'uniq_event_template', unique: true },
      // "Notifications for this user, newest first" (history view, later).
      { key: { userId: 1, createdAt: -1 }, name: 'user_created' },
    ]);
  }

  /**
   * Returns the notification for this event, creating it as 'pending' on first sight.
   * The caller checks the status: 'sent' means this is a duplicate delivery.
   */
  async claim(notification: NewNotification): Promise<NotificationDoc> {
    const filter = { eventId: notification.eventId, template: notification.template };
    const { eventId: _e, template: _t, ...rest } = notification;
    try {
      const doc = await this.collection.findOneAndUpdate(
        filter,
        // Only on insert: a redelivery never overwrites the history of the first attempt.
        { $setOnInsert: { ...rest, status: 'pending', attempts: 0, createdAt: new Date() } },
        { upsert: true, returnDocument: 'after' },
      );
      if (doc) {
        return doc;
      }
    } catch (err) {
      // Two consumers raced on the same event (possible during a rebalance): the other one inserted it.
      if (!(err instanceof MongoServerError && err.code === DUPLICATE_KEY)) {
        throw err;
      }
    }
    const existing = await this.collection.findOne(filter);
    if (!existing) {
      throw new Error(`Notification for event ${notification.eventId} vanished after upsert`);
    }
    return existing;
  }

  async markSent(id: ObjectId, messageId: string): Promise<void> {
    await this.collection.updateOne(
      { _id: id },
      { $set: { status: 'sent', sentAt: new Date(), messageId }, $inc: { attempts: 1 }, $unset: { lastError: '' } },
    );
  }

  async recordFailure(id: ObjectId, error: string): Promise<void> {
    await this.collection.updateOne({ _id: id }, { $set: { lastError: error.slice(0, 500) }, $inc: { attempts: 1 } });
  }

  // Tests and debugging.
  findByEventId(eventId: string): Promise<NotificationDoc[]> {
    return this.collection.find({ eventId }).toArray();
  }
}
