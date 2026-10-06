import { Injectable } from '@nestjs/common';
import { collectDefaultMetrics, Counter, Registry } from 'prom-client';

// Own registry instead of prom-client's global one, so each test app starts from zero.
@Injectable()
export class MetricsService {
  readonly registry = new Registry();

  // outcome: sent | duplicate | invalid (skipped, unparseable) | failed (will be retried)
  readonly events = new Counter({
    name: 'notification_events_total',
    help: 'Kafka events handled by notification-service, by topic and outcome',
    labelNames: ['topic', 'outcome'] as const,
    registers: [this.registry],
  });

  constructor() {
    // Process metrics: CPU, resident memory, heap, event loop lag, GC.
    collectDefaultMetrics({ register: this.registry });
  }

  render(): Promise<string> {
    return this.registry.metrics();
  }

  get contentType(): string {
    return this.registry.contentType;
  }
}
