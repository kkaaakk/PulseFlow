package com.pulseflow.boot;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Java 服务启动入口，负责装配各业务模块。 */
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
@MapperScan(basePackages = {"com.pulseflow.mapper", "com.pulseflow.campaign"}, annotationClass = Mapper.class)
@EnableScheduling
public class PulseFlowApplication {
    /** 启动 Spring Boot 应用，加载各业务模块、Mapper 和配置。 */
    public static void main(String[] args) {
        SpringApplication.run(PulseFlowApplication.class, args);
    }
}
