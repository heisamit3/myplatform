import { type DynamicModule, Module } from '@nestjs/common';
import type { AppConfig } from './config.js';
import { HealthController } from './health/health.controller.js';
import { MetricsService } from './metrics/metrics.service.js';
import { MongoService } from './mongo/mongo.service.js';
import { APP_CONFIG } from './tokens.js';

// forRoot(config) instead of reading process.env inside providers: tests pass their own config.
@Module({})
export class AppModule {
  static forRoot(config: AppConfig): DynamicModule {
    return {
      module: AppModule,
      controllers: [HealthController],
      providers: [{ provide: APP_CONFIG, useValue: config }, MongoService, MetricsService],
    };
  }
}
