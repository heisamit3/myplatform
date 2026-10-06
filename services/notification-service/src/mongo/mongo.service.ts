import { Inject, Injectable, Logger, type OnApplicationShutdown, type OnModuleInit } from '@nestjs/common';
import { type Db, MongoClient } from 'mongodb';
import type { AppConfig } from '../config.js';
import { APP_CONFIG } from '../tokens.js';

// Owns the single MongoClient (it is a connection pool) for the whole process.
@Injectable()
export class MongoService implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(MongoService.name);
  private readonly client: MongoClient;

  constructor(@Inject(APP_CONFIG) config: AppConfig) {
    this.client = new MongoClient(config.mongoUri, {
      appName: 'notification-service',
      // Small pool: one consumer handles one message at a time.
      maxPoolSize: 5,
      serverSelectionTimeoutMS: 5_000,
    });
  }

  // Fail fast: without the database the service can't record anything, so it shouldn't start.
  async onModuleInit(): Promise<void> {
    await this.client.connect();
    this.logger.log(`Connected to MongoDB database '${this.db.databaseName}'`);
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.close();
  }

  // The database named in the URI path (mongodb://.../notification).
  get db(): Db {
    return this.client.db();
  }

  async ping(timeoutMS: number): Promise<boolean> {
    try {
      await this.db.command({ ping: 1 }, { timeoutMS });
      return true;
    } catch {
      return false;
    }
  }
}
