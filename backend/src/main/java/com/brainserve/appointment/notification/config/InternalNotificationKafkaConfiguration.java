package com.brainserve.appointment.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
public class InternalNotificationKafkaConfiguration {

    @Bean
    NewTopic internalCallTopic(
            @Value("${brainserve.notification.internal-call-topic}")
            String topicName,

            @Value("${brainserve.notification.internal-call-topic-partitions:3}")
            int partitions,

            @Value("${brainserve.notification.internal-call-topic-replicas:3}")
            int replicas,

            @Value("${brainserve.notification.internal-call-topic-min-insync-replicas:2}")
            int minInSyncReplicas
    ) {
        if (partitions < 1) {
            throw new IllegalArgumentException(
                    "Kafka topic partitions must be at least 1"
            );
        }

        if (replicas < 1 || replicas > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Kafka topic replicas must be between 1 and "
                            + Short.MAX_VALUE
            );
        }

        if (minInSyncReplicas < 1 || minInSyncReplicas > replicas) {
            throw new IllegalArgumentException(
                    "Kafka min in-sync replicas must be between 1 and the replica count"
            );
        }

        return TopicBuilder.name(topicName)
                .partitions(partitions)
                .replicas(replicas)
                .config(
                        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                        Integer.toString(minInSyncReplicas)
                )
                .build();
    }
}