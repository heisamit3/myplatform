// All configuration comes from environment variables (12-factor). Parsed once at startup;
// a missing required value stops the process with a clear message instead of failing later.
// Defaults suit running on the host next to the compose stack (Kafka localhost:9094, Mailpit SMTP 2525).

export interface AppConfig {
  port: number;
  // Holds the service user's password, so it is never logged.
  mongoUri: string;
  kafka: {
    // Comma-separated host:port list (librdkafka's bootstrap.servers).
    brokers: string;
    groupId: string;
  };
  smtp: {
    host: string;
    port: number;
  };
  mail: {
    // RFC 5322 address, e.g. "MyPlatform <no-reply@myplatform.local>".
    from: string;
    // Link target in emails: the web app the user signs in to.
    webUrl: string;
  };
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    port: intFrom(env, 'PORT', 8082),
    mongoUri: required(env, 'MONGODB_URI'),
    kafka: {
      brokers: stringFrom(env, 'KAFKA_BOOTSTRAP_SERVERS', 'localhost:9094'),
      groupId: stringFrom(env, 'KAFKA_GROUP_ID', 'notification-service'),
    },
    smtp: {
      host: stringFrom(env, 'SMTP_HOST', 'localhost'),
      port: intFrom(env, 'SMTP_PORT', 2525),
    },
    mail: {
      from: stringFrom(env, 'MAIL_FROM', 'MyPlatform <no-reply@myplatform.local>'),
      webUrl: stringFrom(env, 'WEB_URL', 'http://localhost:5173'),
    },
  };
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required environment variable ${name}`);
  }
  return value;
}

function stringFrom(env: NodeJS.ProcessEnv, name: string, fallback: string): string {
  return env[name]?.trim() || fallback;
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
