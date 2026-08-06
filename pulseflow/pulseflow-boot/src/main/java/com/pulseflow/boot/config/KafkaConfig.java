package com.pulseflow.boot.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic rawEventsTopic() {
        return TopicBuilder.name("pulseflow.raw.events")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deliveryTopic() {
        return TopicBuilder.name("pulseflow.delivery")
                .partitions(2)
                .replicas(1)
                .build();
    }
}
