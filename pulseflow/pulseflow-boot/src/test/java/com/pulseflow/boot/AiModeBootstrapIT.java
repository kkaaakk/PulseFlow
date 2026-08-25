package com.pulseflow.boot;

import com.pulseflow.ai.application.CampaignReviewService;
import com.pulseflow.ai.guardrail.AiFieldRegistry;
import com.pulseflow.ai.guardrail.DisabledPiiDetectionClient;
import com.pulseflow.ai.guardrail.FakePiiDetectionClient;
import com.pulseflow.ai.guardrail.PiiDetectionClient;
import com.pulseflow.ai.infrastructure.config.AiAutoConfiguration;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.infrastructure.persistence.PerformanceSummaryCalculator;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AzurePiiDetectionClient;
import com.pulseflow.ai.provider.FakeAiModelClient;
import com.pulseflow.mapper.AttributionRecordMapper;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import com.pulseflow.mapper.ClickEventMapper;
import com.pulseflow.mapper.DeliveryRecordMapper;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.pulseflow.mapper.UserTagMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AI 双模式启动测试 (design §7.1.8).
 *
 * <p>验证两个关键不变量：</p>
 * <ul>
 *   <li><b>AI 关闭模式</b> ({@code pulseflow.ai.enabled=false})：Spring Context
 *       能正常加载，AI Bean <b>不</b>在容器中。</li>
 *   <li><b>AI 开启模式</b> ({@code pulseflow.ai.enabled=true, mock-enabled=true})：
 *       Spring Context 能正常加载，AI 核心 Bean (AiFieldRegistry /
 *       AiModelClient / CampaignReviewService) 在容器中，AiModelClient 是
 *       FakeAiModelClient。</li>
 * </ul>
 *
 * <p>使用 {@link ApplicationContextRunner} 做轻量上下文测试，不启 Tomcat /
 * Kafka / Redis / XXL-JOB。所有 MyBatis mapper 用 Mockito mock 提供，避免
 * 连真实 DB —— 这样可以独立验证 {@code @ConditionalOnProperty} +
 * {@code @ComponentScan} 的双模式装配逻辑，不受 Docker 可用性影响。</p>
 *
 * <p>数据库迁移验证见 {@link FlywayMigrationIT}，需 Docker 环境。</p>
 */
class AiModeBootstrapIT {

    /**
     * Lightweight context runner that loads only the AI auto-configuration.
     *
     * <p>All MyBatis mappers and external service dependencies are mocked so
     * the full {@code com.pulseflow.ai} ComponentScan can succeed without a
     * running database / Kafka / Redis. This isolates the test to just
     * verifying the {@code @ConditionalOnProperty} + {@code @ComponentScan}
     * wiring logic.</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiAutoConfiguration.class))
            // In production spring-boot-starter-actuator supplies a MeterRegistry;
            // the lightweight ApplicationContextRunner does not load actuator's
            // auto-configuration, so provide a SimpleMeterRegistry to mirror
            // production wiring (AiMetrics also tolerates its absence).
            .withBean(MeterRegistry.class, () -> new SimpleMeterRegistry())
            // AI module mappers
            .withBean(CampaignMapper.class, () -> mock(CampaignMapper.class))
            .withBean(CampaignRuleMapper.class, () -> mock(CampaignRuleMapper.class))
            .withBean(com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiDraftMapper.class,
                    () -> mock(com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiDraftMapper.class))
            .withBean(com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiReviewMapper.class,
                    () -> mock(com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiReviewMapper.class))
            .withBean(com.pulseflow.ai.infrastructure.persistence.mapper.CampaignPerformanceSummaryMapper.class,
                    () -> mock(com.pulseflow.ai.infrastructure.persistence.mapper.CampaignPerformanceSummaryMapper.class))
            .withBean(com.pulseflow.ai.infrastructure.persistence.mapper.AiGenerationRecordMapper.class,
                    () -> mock(com.pulseflow.ai.infrastructure.persistence.mapper.AiGenerationRecordMapper.class))
            // Performance calculator deps
            .withBean(DeliveryTaskMapper.class, () -> mock(DeliveryTaskMapper.class))
            .withBean(DeliveryRecordMapper.class, () -> mock(DeliveryRecordMapper.class))
            .withBean(ClickEventMapper.class, () -> mock(ClickEventMapper.class))
            .withBean(AttributionRecordMapper.class, () -> mock(AttributionRecordMapper.class))
            // Audience preview / metrics aggregator deps
            .withBean(UserProfileMapper.class, () -> mock(UserProfileMapper.class))
            .withBean(UserTagMapper.class, () -> mock(UserTagMapper.class))
            .withBean(UserBehaviorSummaryMapper.class, () -> mock(UserBehaviorSummaryMapper.class));

    // ------------------------------------------------------------------
    // Mode 1: AI disabled (default)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AI 关闭模式 (pulseflow.ai.enabled=false)")
    class AiDisabledMode {

        @Test
        @DisplayName("Spring Context 加载成功，所有 AI Bean 缺失")
        void contextLoadsWithAiDisabled() {
            runner
                    .withPropertyValues("pulseflow.ai.enabled=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(AiAutoConfiguration.class);
                        assertThat(context).doesNotHaveBean(AiFeatureProperties.class);
                        assertThat(context).doesNotHaveBean(CampaignReviewService.class);
                        assertThat(context).doesNotHaveBean(AiFieldRegistry.class);
                        assertThat(context).doesNotHaveBean(AiModelClient.class);
                        assertThat(context).doesNotHaveBean(PiiDetectionClient.class);
                    });
        }
    }

    // ------------------------------------------------------------------
    // Mode 2: AI enabled (mock)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AI 开启模式 (pulseflow.ai.enabled=true, mock-enabled=true)")
    class AiEnabledMode {

        @Test
        @DisplayName("Spring Context 加载成功，AI 核心 Bean 全部装配")
        void contextLoadsWithAiEnabled() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(AiAutoConfiguration.class);
                        assertThat(context).hasSingleBean(AiFeatureProperties.class);
                        assertThat(context).hasSingleBean(AiFieldRegistry.class);
                        assertThat(context).hasSingleBean(AiModelClient.class);
                        assertThat(context).hasSingleBean(PiiDetectionClient.class);
                        assertThat(context).hasSingleBean(CampaignReviewService.class);
                        assertThat(context).hasSingleBean(AiAuditService.class);
                        assertThat(context).hasSingleBean(AiMetrics.class);
                        assertThat(context).hasSingleBean(PerformanceSummaryCalculator.class);
                    });
        }

        @Test
        @DisplayName("真实 AI + PII disabled 在启动阶段失败")
        void realAiRequiresPiiGuardrail() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=false",
                            "pulseflow.ai.provider=openai-compatible",
                            "pulseflow.ai.base-url=http://localhost:9999",
                            "pulseflow.ai.api-key=test-key",
                            "pulseflow.ai.model=test-model",
                            "pulseflow.ai.pii.enabled=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("Real AI provider requires PII guardrail to be enabled");
                    });
        }

        @Test
        @DisplayName("PII disabled keeps local guardrail and does not require Azure credentials")
        void piiDisabledUsesNoopClient() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=true",
                            "pulseflow.ai.pii.enabled=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(PiiDetectionClient.class))
                                .isInstanceOf(DisabledPiiDetectionClient.class);
                    });
        }

        @Test
        @DisplayName("PII enabled in mock mode uses FakePiiDetectionClient without Azure credentials")
        void piiEnabledMockDoesNotCallAzure() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=true",
                            "pulseflow.ai.pii.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(PiiDetectionClient.class))
                                .isInstanceOf(FakePiiDetectionClient.class);
                    });
        }

        @Test
        @DisplayName("real PII mode fails startup early when Azure credentials are missing")
        void piiEnabledRealModeValidatesCredentials() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=false",
                            "pulseflow.ai.base-url=http://localhost:9999",
                            "pulseflow.ai.api-key=test-key",
                            "pulseflow.ai.model=test-model",
                            "pulseflow.ai.pii.enabled=true",
                            "pulseflow.ai.pii.mock-enabled=false",
                            "pulseflow.ai.pii.endpoint=",
                            "pulseflow.ai.pii.api-key=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("pulseflow.ai.pii.enabled=true requires AZURE_LANGUAGE_ENDPOINT and AZURE_LANGUAGE_KEY when mock mode is disabled");
                    });
        }

        @Test
        @DisplayName("AiFeatureProperties 正确读取 enabled=true / mock-enabled=true")
        void aiFeaturePropertiesBoundCorrectly() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=true")
                    .run(context -> {
                        AiFeatureProperties props = context.getBean(AiFeatureProperties.class);
                        assertThat(props.isEnabled()).isTrue();
                        assertThat(props.isMockEnabled()).isTrue();
                    });
        }

        @Test
        @DisplayName("AiModelClient 是 FakeAiModelClient（mock 模式，不连外部 API）")
        void aiModelClientIsFake() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=true")
                    .run(context -> {
                        AiModelClient client = context.getBean(AiModelClient.class);
                        assertThat(client).isInstanceOf(FakeAiModelClient.class);
                    });
        }

        @Test
        @DisplayName("AiFieldRegistry 初始化了 12 个字段")
        void aiFieldRegistryInitialised() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=true")
                    .run(context -> {
                        AiFieldRegistry registry = context.getBean(AiFieldRegistry.class);
                        assertThat(registry.enabledFields()).hasSize(12);
                    });
        }

        @Test
        @DisplayName("关闭 mock-enabled 时 AiModelClient 不是 FakeAiModelClient")
        void aiModelClientNotFakeWhenMockDisabled() {
            runner
                    .withPropertyValues(
                            "pulseflow.ai.enabled=true",
                            "pulseflow.ai.mock-enabled=false",
                            "pulseflow.ai.provider=openai-compatible",
                            "pulseflow.ai.base-url=http://localhost:9999",
                            "pulseflow.ai.api-key=test-key",
                            "pulseflow.ai.model=test-model",
                            "pulseflow.ai.pii.enabled=true",
                            "pulseflow.ai.pii.endpoint=https://language.example.test",
                            "pulseflow.ai.pii.api-key=test-language-key",
                            "pulseflow.ai.pii.language=zh-hans",
                            "pulseflow.ai.pii.timeout-seconds=5")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        AiModelClient client = context.getBean(AiModelClient.class);
                        assertThat(client).isNotInstanceOf(FakeAiModelClient.class);
                        assertThat(context.getBean(PiiDetectionClient.class))
                                .isInstanceOf(AzurePiiDetectionClient.class);
                    });
        }
    }

    // ------------------------------------------------------------------
    // Flyway migration verification (requires Docker)
    // ------------------------------------------------------------------

    /**
     * Verifies Flyway V1~V5 migration scripts run cleanly on MySQL 8.0 and
     * that V4/V5's state-machine columns, retry-split columns, rebuilt scan
     * index and campaign ownership column exist.
     *
     * <p>Requires Docker. Skipped automatically by surefire (IT naming
     * convention); run via {@code mvn verify -pl pulseflow-boot
     * -Dit.test=AiModeBootstrapIT$FlywayMigrationIT} when Docker is available.</p>
     */
    // Default-skipped: Testcontainers' docker-java currently returns Status 400
    // against Docker Desktop 29.x's _ping on this host, so the Testcontainers
    // path is gated behind PULSEFLOW_TEST_DOCKER=true for CI. Local migration
    // verification is instead performed via a docker-CLI MySQL container
    // (see docs/ai-stage-7.1-report.md §6), which exercises the exact same
    // V1~V4 DDL against a real MySQL 8.0 instance.
    @EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
    @Testcontainers
    @Nested
    @DisplayName("Flyway V1~V4 迁移验证 (Testcontainers MySQL 8.0, 需 Docker)")
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
        @DisplayName("V1~V5 迁移成功，campaign_ai_review 含状态机列+重试调度索引，campaign 含 created_by")
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
        @DisplayName("V1~V5 迁移成功，核心表与 AI 表全部存在")
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
                        "campaign_performance_summary", "ai_generation_record");
            }
        }
    }
}
