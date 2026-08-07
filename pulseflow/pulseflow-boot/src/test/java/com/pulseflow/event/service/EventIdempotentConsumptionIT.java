package com.pulseflow.event.service;

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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
@SpringBootTest(classes = EventIdempotentConsumptionIT.TestApp.class)
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
     * 最小化配置：只扫描 mapper，导入 EventPersistenceService + MetaObjectHandler，
     * 排除 Kafka/Redisson 自动配置（这两个组件的 bean 不在本测试范围内）。
     *
     * <p>使用 {@link SpringBootConfiguration}（而非 {@code @TestConfiguration}）作为
     * {@code @SpringBootTest(classes=...)} 的主配置源。{@code @TestConfiguration}
     * 不是 {@code @SpringBootConfiguration}，作为 classes 参数时 Spring Boot 会
     * 找不到启动配置类，抛 "Unable to find a @SpringBootConfiguration"。</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            KafkaAutoConfiguration.class,
            org.redisson.spring.starter.RedissonAutoConfiguration.class
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
}
