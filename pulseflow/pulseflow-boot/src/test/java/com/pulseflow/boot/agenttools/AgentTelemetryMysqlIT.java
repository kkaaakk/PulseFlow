package com.pulseflow.boot.agenttools;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The same HTTP/service/JDBC trace contract against the real MySQL driver in CI. */
@EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
@Testcontainers
class AgentTelemetryMysqlIT extends AgentTelemetryTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pulseflow_otel_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Override protected String expectedDatabaseSystem() { return "mysql"; }
}
