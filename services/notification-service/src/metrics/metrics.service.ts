import { Injectable } from '@nestjs/common';
import { collectDefaultMetrics, Registry } from 'prom-client';

// Own registry instead of prom-client's global one, so each test app starts from zero.
@Injectable()
export class MetricsService {
  readonly registry = new Registry();

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
