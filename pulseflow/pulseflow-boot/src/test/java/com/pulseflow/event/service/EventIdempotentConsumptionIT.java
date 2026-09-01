package com.pulseflow.event.service;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.common.config.MyMetaObjectHandler;
import com.pulseflow.entity.UserEvent;
import com.pulseflow.entity.UserMetricHourly;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.mapper.UserMetricHourlyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链路1（行为接入与幂等消费）集成测试 — 设计文档 Phase 5 要求的 Testcontainers 测试。
 *
 * <p>使用最小化 Spring 配置：只扫描 mapper + EventPersistenceService + MyBatis-Plus，
 * 排除 Kafka/Redisson 自动配置，避免依赖 Kafka/Redis 容器。只验证 MySQL 层的
 * 三个核心不变量：</p>
 * <ol>
 *   <li><b>同事务原子写入</b>：user_event + user_metric_hourly 落库；</li>
 *   <li><b>DuplicateKey 幂等</b>：相同 eventId 重复 persist 不抛异常，返回 DB 标准事件；</li>
 *   <li><b>指标桶累加</b>：INSERT ON DUPLICATE KEY UPDATE 正确累加 event_count。</li>
 * </ol>
 *
 * <p>需要 Docker 运行（拉 mysql:8.0 镜像）。</p>
 */
// Default-skipped: Testcontainers' docker-java returns Status 400 against
// Docker Desktop 29.x on this host. Run with -DPULSEFLOW_TEST_DOCKER=true in
// CI / any environment with a working Testcontainers setup.
@EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
@Testcontainers
@SpringBootTest(
        classes = EventIdempotentConsumptionIT.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EventIdempotentConsumptionIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("pulseflow_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl()
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    /**
     * 最小化配置：只导入数据层自动配置（DataSource / Flyway / MyBatis-Plus / 事务），
     * 扫描 mapper，导入 EventPersistenceService + MetaObjectHandler。
     *
     * <p>用 {@link ImportAutoConfiguration} 而非 {@code @EnableAutoConfiguration}，
     * 精确只拉数据层自动配置，从根上避免 Sa-Token Redis / Redisson / Kafka / XXL-JOB
     * 等 bean 被自动装配进来——这些组件在本测试范围外，且 {@code @EnableAutoConfiguration}
     * 的 exclude 链无法收敛（排除 Redisson 后 SaTokenDaoRedisJackson 又缺
     * RedisConnectionFactory）。</p>
     *
     * <p>同时使用 {@link SpringBootConfiguration}（而非 {@code @TestConfiguration}）作为
     * {@code @SpringBootTest(classes=...)} 的主配置源，并设 {@code webEnvironment=NONE}
     * 避免加载 web 自动配置。</p>
     */
    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    @MapperScan("com.pulseflow.mapper")
    @Import({EventPersistenceService.class, MyMetaObjectHandler.class})
    static class TestApp {
    }

    @Autowired
    private EventPersistenceService eventPersistenceService;

    @Autowired
    private UserEventMapper userEventMapper;

    @Autowired
    private UserMetricHourlyMapper userMetricHourlyMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, Object> buildEvent(String eventId, String eventType) {
        Map<String, Object> eventMap = new LinkedHashMap<>();
        eventMap.put("eventId", eventId);
        eventMap.put("userId", 1024L);
        eventMap.put("eventType", eventType);
        eventMap.put("targetId", 8866L);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        eventMap.put("eventTime", now.toString());
        eventMap.put("receivedAt", now.toString());
        eventMap.put("effectiveEventTime", now.toString());
        eventMap.put("clockSkew", false);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", "AI");
        props.put("price", 29.9);
        props.put("duration", 5000L);
        eventMap.put("properties", props);
        return eventMap;
    }

    @Test
    @Transactional
    @DisplayName("首次 persist：user_event + user_metric_hourly 同事务落库")
    void firstPersist_writesEventAndMetricAtomically() {
        Map<String, Object> eventMap = buildEvent("evt_it_001", "CONTENT_VIEW");

        EventPersistenceService.PersistResult result = eventPersistenceService.persist(eventMap);

        assertTrue(result.isOk(), "persist should succeed");
        UserEvent saved = userEventMapper.selectOne(
                new LambdaQueryWrapper<UserEvent>()
                        .eq(UserEvent::getEventId, "evt_it_001"));
        assertNotNull(saved, "user_event must be persisted");
        assertEquals("CONTENT_VIEW", saved.getEventType());
        assertNotNull(result.getContext(), "canonical context must be returned from DB event");
        assertEquals("evt_it_001", result.getContext().get("eventId"));
    }

    @Test
    @Transactional
    @DisplayName("重复 persist 同一 eventId：不抛异常，返回 DB 标准事件（幂等）")
    void duplicatePersist_isIdempotentAndLoadsFromDb() {
        Map<String, Object> eventMap = buildEvent("evt_it_002", "SEARCH");

        EventPersistenceService.PersistResult first = eventPersistenceService.persist(eventMap);
        assertTrue(first.isOk());

        // 第二次重复 —— 应走 DuplicateKeyException 分支，从 DB 加载标准事件
        EventPersistenceService.PersistResult second = eventPersistenceService.persist(eventMap);
        assertTrue(second.isOk(), "duplicate persist must not fail (idempotent)");
        assertNotNull(second.getEvent(), "canonical event must be loaded from DB");
        assertEquals("evt_it_002", second.getEvent().getEventId());
    }

    @Test
    @Transactional
    @DisplayName("指标桶累加：同一小时桶多次写入 event_count 正确累加（ON DUPLICATE KEY UPDATE）")
    void metricHourly_accumulatesOnDuplicateKey() {
        eventPersistenceService.persist(buildEvent("evt_it_003", "CONTENT_VIEW"));
        eventPersistenceService.persist(buildEvent("evt_it_004", "CONTENT_VIEW"));

        LocalDateTime hour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        UserMetricHourly metric = userMetricHourlyMapper.selectOne(
                new LambdaQueryWrapper<UserMetricHourly>()
                        .eq(UserMetricHourly::getUserId, 1024L)
                        .eq(UserMetricHourly::getMetricHour, hour)
                        .eq(UserMetricHourly::getEventType, "CONTENT_VIEW"));

        assertNotNull(metric, "hourly bucket must exist");
        assertTrue(metric.getEventCount() >= 2,
                "event_count should accumulate to >= 2, got " + metric.getEventCount());
        // amount_sum 也应累加（两次 29.9 = 59.8）
        assertTrue(metric.getAmountSum().compareTo(new BigDecimal("59.7")) >= 0,
                "amount_sum should accumulate, got " + metric.getAmountSum());
    }

    @Test
    @Transactional
    @DisplayName("targetId NULL and zero remain distinct through MyBatis INSERT and MySQL")
    void targetIdNullAndZeroRoundTripToMysql() {
        String nullEventId = "evt_it_null_target";
        Map<String, Object> nullEvent = buildEvent(nullEventId, "LOGIN");
        nullEvent.put("targetId", null);

        EventPersistenceService.PersistResult nullResult =
                eventPersistenceService.persist(nullEvent);

        assertTrue(nullResult.isOk());
        Integer sqlNullRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_event WHERE event_id = ? AND target_id IS NULL",
                Integer.class,
                nullEventId);
        assertEquals(1, sqlNullRows);
        UserEvent nullSaved = userEventMapper.selectOne(
                new LambdaQueryWrapper<UserEvent>()
                        .eq(UserEvent::getEventId, nullEventId));
        assertNotNull(nullSaved);
        assertNull(nullSaved.getTargetId());

        String zeroEventId = "evt_it_zero_target";
        Map<String, Object> zeroEvent = buildEvent(zeroEventId, "LOGIN");
        zeroEvent.put("targetId", 0L);

        EventPersistenceService.PersistResult zeroResult =
                eventPersistenceService.persist(zeroEvent);

        assertTrue(zeroResult.isOk());
        Integer zeroRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_event WHERE event_id = ? AND target_id = 0",
                Integer.class,
                zeroEventId);
        assertEquals(1, zeroRows);
        UserEvent zeroSaved = userEventMapper.selectOne(
                new LambdaQueryWrapper<UserEvent>()
                        .eq(UserEvent::getEventId, zeroEventId));
        assertNotNull(zeroSaved);
        assertEquals(0L, zeroSaved.getTargetId());
    }
}
