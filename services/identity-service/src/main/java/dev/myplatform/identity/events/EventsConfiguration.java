package dev.myplatform.identity.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topics this service owns. Spring's KafkaAdmin creates missing ones at startup; the broker has
 * auto-create off (ADR 0009). If Kafka is down at startup, the app still starts and logs the failure.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling // OutboxRelay polls on the scheduler thread
class EventsConfiguration {

    @Bean
    NewTopic userRegisteredTopic(EventsProperties properties) {
        return TopicBuilder.name(EventType.USER_REGISTERED.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

}
