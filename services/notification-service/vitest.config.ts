import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['src/**/*.spec.ts', 'test/**/*.spec.ts'],
    // Integration tests start containers (Testcontainers); the first image pull can take a while.
    hookTimeout: 180_000,
    testTimeout: 60_000,
    // One file at a time: each integration file starts its own containers, and RAM is tight (8 GB host).
    fileParallelism: false,
  },
});
