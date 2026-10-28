package com.pulseflow.boot.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 声明原始事件和触达任务的 Kafka 主题。 */
@Configuration
public class KafkaConfig {

    /** 声明原始事件主题的分区与副本配置，供接入和消费链路使用。 */
    @Bean
    public NewTopic rawEventsTopic() {
        return TopicBuilder.name("pulseflow.raw.events")
                .partitions(4)
                .replicas(1)
                .build();
    }

    /** 声明触达任务主题，供投递服务发布和渠道消费者订阅。 */
    @Bean
    public NewTopic deliveryTopic() {
        return TopicBuilder.name("pulseflow.delivery")
                .partitions(2)
                .replicas(1)
                .build();
    }
}
