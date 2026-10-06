import { Controller, Get, Header, HttpStatus, Res } from '@nestjs/common';
import type { Response } from 'express';
import { UserRegisteredConsumer } from '../kafka/user-registered.consumer.js';
import { MetricsService } from '../metrics/metrics.service.js';
import { MongoService } from '../mongo/mongo.service.js';

// Same contract as the Spring services: /health = liveness (process only), /ready = readiness
// (dependencies), /metrics = Prometheus text format.
@Controller()
export class HealthController {
  constructor(
    private readonly mongo: MongoService,
    private readonly consumer: UserRegisteredConsumer,
    private readonly metrics: MetricsService,
  ) {}

  // No dependency checks: a database outage must not make Kubernetes restart this pod.
  @Get('health')
  health(): { status: string } {
    return { status: 'UP' };
  }

  @Get('ready')
  async ready(@Res({ passthrough: true }) res: Response): Promise<{ status: string; components: Record<string, string> }> {
    const components = {
      mongo: (await this.mongo.ping(2_000)) ? 'UP' : 'DOWN',
      kafka: this.consumer.isConnected() ? 'UP' : 'DOWN',
    };
    const up = Object.values(components).every((s) => s === 'UP');
    if (!up) {
      res.status(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return { status: up ? 'UP' : 'DOWN', components };
  }

  @Get('metrics')
  @Header('Cache-Control', 'no-store')
  async metricsText(@Res({ passthrough: true }) res: Response): Promise<string> {
    res.type(this.metrics.contentType);
    return this.metrics.render();
  }
}
