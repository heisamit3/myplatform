package dev.myplatform.identity.events;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka topic and outbox relay settings.
 *
 * @param partitions        per topic. Upper bound for parallel consumers in one consumer group.
 * @param replicationFactor copies of each partition. 1 locally (single broker); 3 on a real cluster.
 */
@ConfigurationProperties("identity.events")
record EventsProperties(
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue Outbox outbox) {

    /**
     * @param pollInterval    pause between relay runs while things are healthy. Upper bound on event latency.
     * @param batchSize       rows sent per run (one transaction). A full batch triggers the next run at once.
     * @param sendTimeout     how long a run waits for Kafka acks before it counts as failed. The DB transaction
     *                        (and a pooled connection) is held meanwhile, so keep it short.
     * @param maxBackoff      longest pause after repeated failures (Kafka down). Doubles from pollInterval.
     * @param retention       how long published rows are kept (debugging, manual replays) before deletion.
     * @param cleanupInterval how often published rows past the retention are deleted.
     */
    record Outbox(
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("20s") Duration sendTimeout,
            @DefaultValue("30s") Duration maxBackoff,
            @DefaultValue("7d") Duration retention,
            @DefaultValue("1h") Duration cleanupInterval) {
    }

}
