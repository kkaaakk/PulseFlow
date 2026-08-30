package com.pulseflow.boot;

import com.pulseflow.ai.infrastructure.config.AiAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.pulseflow.boot",
        "com.pulseflow.campaign",
        "com.pulseflow.common",
        "com.pulseflow.entity",
        "com.pulseflow.event",
        "com.pulseflow.job",
        "com.pulseflow.mapper",
        "com.pulseflow.profile",
        "com.pulseflow.simulator"
})
@Import(AiAutoConfiguration.class)
@MapperScan("com.pulseflow.mapper")
@EnableScheduling
public class PulseFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(PulseFlowApplication.class, args);
    }
}
