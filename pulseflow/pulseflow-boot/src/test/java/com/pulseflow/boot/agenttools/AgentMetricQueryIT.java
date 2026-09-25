package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the actual Flyway schema in CI; local Docker tests are opt-in. */
@EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
@Testcontainers
class AgentMetricQueryIT {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("pulseflow_agent_tool_test")
            .withUsername("test")
            .withPassword("test");

    static AgentMetricService service;

    @BeforeAll
    static void prepare() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        service = new AgentMetricService(new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("INSERT INTO delivery_task (id,campaign_id,user_id,dedup_key,channel) VALUES "
                + "(101,9,1001,'agent-test-101','PUSH'),"
                + "(102,9,1002,'agent-test-102','PUSH'),"
                + "(103,9,1003,'agent-test-103','PUSH'),"
                + "(104,9,1004,'agent-test-104','PUSH')");
        jdbc.execute("INSERT INTO delivery_record (task_id,user_id,campaign_id,channel,status,sent_at) VALUES "
                + "(101,1001,9,'PUSH','SENT','2026-09-02 10:00:00'),"
                + "(102,1002,9,'PUSH','SENT','2026-09-02 10:00:00'),"
                + "(103,1003,9,'PUSH','SENT','2026-09-02 10:00:00'),"
                + "(104,1004,9,'PUSH','FAILED','2026-09-02 10:00:00')");
        jdbc.execute("INSERT INTO click_event (id,user_id,task_id,click_time) VALUES "
                + "(201,1001,101,'2026-09-02 11:00:00'),"
                + "(202,1001,101,'2026-09-02 11:01:00'),"
                + "(203,1002,102,'2026-09-02 11:02:00')");
        jdbc.execute("INSERT INTO attribution_record "
                + "(click_event_id,target_event_id,user_id,campaign_id,task_id,credited_at) VALUES "
                + "(201,'agent-conversion-1',1001,9,101,'2026-09-02 12:00:00'),"
                + "(203,'agent-conversion-2',1002,9,102,'2026-09-02 12:05:00')");
    }

    @Test
    void periodRatesMatchCalculatorSemanticsAndAttributionIsAggregateOnly() {
        TimeRange range = new TimeRange(OffsetDateTime.parse("2026-09-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-09-08T00:00:00+08:00"));
        Filter campaign = new Filter(FilterField.CAMPAIGN_ID, Operator.EQ, List.of("9"));
        QueryResponse ctr = service.query(new QueryRequest(Metric.CTR, range,
                List.of(campaign), List.of(Dimension.CHANNEL), 10));
        assertThat(ctr.rows()).hasSize(1);
        assertThat(ctr.rows().get(0).value()).isEqualByComparingTo("0.6667");
        assertThat(ctr.sampleSize()).isEqualTo(3);
        QueryResponse conversion = service.query(new QueryRequest(Metric.CONVERSION_RATE, range,
                List.of(campaign), List.of(Dimension.CHANNEL), 10));
        assertThat(conversion.rows().get(0).value()).isEqualByComparingTo("1.0000");
        AttributionResponse attribution = service.attribution(new AttributionRequest(range,
                List.of(campaign), AttributionDimension.MODEL, 10));
        assertThat(attribution.rows().get(0).attributionCount()).isEqualTo(2);
        assertThat(attribution.rows().get(0).uniqueConverters()).isEqualTo(2);
        assertThat(attribution.toString()).doesNotContain("1001", "1002", "target_event_id");
    }
}
