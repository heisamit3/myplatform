import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const app = await NestFactory.create(AppModule.forRoot(config));
// SIGTERM (docker stop, Kubernetes) runs the shutdown hooks: close the Kafka consumer and Mongo cleanly.
app.enableShutdownHooks();
await app.listen(config.port);
