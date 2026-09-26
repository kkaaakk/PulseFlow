package com.pulseflow.boot;

import com.pulseflow.campaign.validation.CampaignFieldRegistry;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Business components bootstrap without the removed Java AI runtime. */
class BusinessRuntimeBootstrapIT {
    @Import({CampaignFieldRegistry.class, CampaignDslValidator.class})
    static class BusinessConfiguration {}

    @Test
    void businessValidationStartsWithoutAiRuntime() {
        new ApplicationContextRunner().withUserConfiguration(BusinessConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CampaignDslValidator.class);
                    assertThat(context).hasSingleBean(CampaignFieldRegistry.class);
                });
    }

    // ------------------------------------------------------------------
    // Flyway migration verification (requires Docker)
    // ------------------------------------------------------------------

    /**
     * Verifies Flyway V1~V6 migration scripts run cleanly on MySQL 8.0 and
     * that V4/V5's state-machine columns, retry-split columns, rebuilt scan
     * index and campaign ownership column exist.
     *
     * <p>Requires Docker. Skipped automatically by surefire (IT naming
     * convention); run via {@code mvn verify -pl pulseflow-boot
     * -Dit.test=BusinessRuntimeBootstrapIT$FlywayMigrationIT} when Docker is available.</p>
     */
    // Docker-backed migration tests are opt-in locally to avoid starting
    // containers during ordinary unit-test runs. CI and full validation set
    // PULSEFLOW_TEST_DOCKER=true.
    @EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
    @Testcontainers
    @Nested
    @DisplayName("Flyway V1~V6 迁移验证 (Testcontainers MySQL 8.0, 需 Docker)")
    class FlywayMigrationIT {

        @Container
        @SuppressWarnings("resource")
        final MySQLContainer<?> mysql = new MySQLContainer<>(
                DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("pulseflow_flyway_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);

        @Test
        @DisplayName("V1~V6 迁移成功，review 状态机、重试索引和 campaign 归属存在")
        void flywayMigrationCreatesStateMachineColumns() throws Exception {
            org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            flyway.migrate();

            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 java.sql.Statement stmt = conn.createStatement()) {
                // V4 状态机列 (locked_by/locked_at/version) + V5 重试拆分列
                // (failure_code/retryable/retry_count/next_retry_at) = 共 7 列
                try (java.sql.ResultSet rs = stmt.executeQuery(
                        "SHOW COLUMNS FROM campaign_ai_review WHERE Field IN ("
                                + "'locked_by','locked_at','version',"
                                + "'failure_code','retryable','retry_count','next_retry_at')")) {
                    int count = 0;
                    while (rs.next()) count++;
                    assertThat(count).isEqualTo(7);
                }
                // V5 重建了扫描索引：DROP idx_ai_review_status -> CREATE idx_ai_review_status_retry
                try (java.sql.ResultSet rs = stmt.executeQuery(
                        "SHOW INDEX FROM campaign_ai_review WHERE Key_name = 'idx_ai_review_status_retry'")) {
                    assertThat(rs.next()).isTrue();
                }
                // 旧索引名应已被 V5 删除
                try (java.sql.ResultSet rs = stmt.executeQuery(
                        "SHOW INDEX FROM campaign_ai_review WHERE Key_name = 'idx_ai_review_status'")) {
                    assertThat(rs.next()).isFalse();
                }
                // V5 资源归属列
                try (java.sql.ResultSet rs = stmt.executeQuery(
                        "SHOW COLUMNS FROM campaign WHERE Field = 'created_by'")) {
                    assertThat(rs.next()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("V1~V6 迁移成功，核心表、历史 AI 表和 Agent 授权表存在")
        void coreTablesExistAfterMigration() throws Exception {
            org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            flyway.migrate();

            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(
                         "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                                 + mysql.getDatabaseName() + "'")) {
                java.util.Set<String> tables = new java.util.HashSet<>();
                while (rs.next()) tables.add(rs.getString(1));
                assertThat(tables).contains(
                        "campaign", "campaign_rule", "user_event", "user_metric_hourly",
                        "campaign_ai_draft", "campaign_ai_review",
                        "campaign_performance_summary", "ai_generation_record",
                        "agent_investigation_owner", "agent_campaign_draft_grant");
            }
        }
    }
}
