// All configuration comes from environment variables (12-factor). Parsed once at startup;
// a missing required value stops the process with a clear message instead of failing later.

export interface AppConfig {
  port: number;
  // Holds the service user's password, so it is never logged.
  mongoUri: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    port: intFrom(env, 'PORT', 8082),
    mongoUri: required(env, 'MONGODB_URI'),
  };
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required environment variable ${name}`);
  }
  return value;
}

function intFrom(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const raw = env[name]?.trim();
  if (!raw) {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`Environment variable ${name} must be a positive integer, got '${raw}'`);
  }
  return value;
}
