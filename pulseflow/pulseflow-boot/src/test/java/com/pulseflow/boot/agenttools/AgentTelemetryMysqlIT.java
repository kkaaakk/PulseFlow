package com.pulseflow.boot.agenttools;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 验证 AgentTelemetryMysql 的业务行为和边界条件。
 * The same HTTP/service/JDBC trace contract against the real MySQL driver in CI. */
@EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
@Testcontainers
class AgentTelemetryMysqlIT extends AgentTelemetryTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pulseflow_otel_test").withUsername("test").withPassword("test");

    /** 注入测试容器的 MySQL 连接配置，使遥测集成测试连接隔离数据库。 */
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    /** 返回当前测试所期望的数据库类型，供遥测断言复用。 */
    @Override protected String expectedDatabaseSystem() { return "mysql"; }
}
