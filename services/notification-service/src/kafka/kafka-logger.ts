import type { KafkaJS } from '@confluentinc/kafka-javascript';
import { Logger } from '@nestjs/common';

// Routes the Kafka client's log lines (default: raw objects on console.*) through Nest's logger,
// so they share the format and context of the service's own logs.
// The client calls setLogLevel() itself, from the librdkafka log_level (INFO by default).
export function nestKafkaLogger(context: string): KafkaJS.Logger {
  const logger = new Logger(context);
  let level = 3; // logLevel.INFO (a const enum value; the enum itself only exists as a type here)

  const format = (message: string, extra?: object): string => {
    const name = (extra as { name?: unknown } | undefined)?.name;
    return typeof name === 'string' && name ? `${message} (${name})` : message;
  };

  const self: KafkaJS.Logger = {
    error: (message, extra) => level >= 1 && logger.error(format(message, extra)),
    warn: (message, extra) => level >= 2 && logger.warn(format(message, extra)),
    info: (message, extra) => level >= 3 && logger.log(format(message, extra)),
    debug: (message, extra) => level >= 4 && logger.debug(format(message, extra)),
    namespace: () => self,
    setLogLevel: (newLevel) => {
      level = newLevel;
    },
  };
  return self;
}
