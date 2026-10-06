package dev.myplatform.identity.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka topic settings.
 *
 * @param partitions        per topic. Upper bound for parallel consumers in one consumer group.
 * @param replicationFactor copies of each partition. 1 locally (single broker); 3 on a real cluster.
 */
@ConfigurationProperties("identity.events")
record EventsProperties(
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicationFactor) {
}
