package com.pulseflow.boot.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    /**
     * 声明四分区、单副本的原始事件 Topic，供按用户键分区的事件接入链路使用。
     */
    @Bean
    public NewTopic rawEventsTopic() {
        return TopicBuilder.name("pulseflow.raw.events")
                .partitions(4)
                .replicas(1)
                .build();
    }

    /**
     * 声明两分区、单副本的触达任务 Topic，供异步触达消费者处理。
     */
    @Bean
    public NewTopic deliveryTopic() {
        return TopicBuilder.name("pulseflow.delivery")
                .partitions(2)
                .replicas(1)
                .build();
    }
}
