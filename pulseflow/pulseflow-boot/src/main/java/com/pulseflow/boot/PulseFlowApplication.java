package com.pulseflow.boot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.pulseflow")
@MapperScan({"com.pulseflow.mapper", "com.pulseflow.ai.infrastructure.persistence.mapper"})
@EnableScheduling
public class PulseFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(PulseFlowApplication.class, args);
    }
}
