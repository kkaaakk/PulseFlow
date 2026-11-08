package com.pulseflow.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.boot.agenttools.AgentToolDtos.*;
import com.pulseflow.boot.web.WebQueryService;
import com.pulseflow.campaign.analytics.PerformanceSummaryCalculator;
import com.pulseflow.campaign.attribution.AttributionService;
import com.pulseflow.campaign.attribution.AttributionTaskConsumer;
import com.pulseflow.campaign.attribution.ClickEventService;
import com.pulseflow.event.service.EventPersistenceService;
import com.pulseflow.event.service.TrustedEventSource;
import com.pulseflow.campaign.delay.DelayedTaskManager;
import com.pulseflow.campaign.dsl.AudienceCondition;
import com.pulseflow.campaign.dsl.AudienceGroup;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.preview.SqlAudiencePreviewService;
import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.common.util.DedupKeyUtil;
import com.pulseflow.job.handler.CampaignSelectionJob;
import com.pulseflow.job.handler.CompensationJob;
import com.pulseflow.job.handler.DailyMetricJob;
import com.pulseflow.job.handler.WindowMetricJob;
import com.pulseflow.profile.service.ProfileService;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real HTTP -> Kafka -> MySQL/Redis -> attribution -> Web/Agent acceptance.
 * Only the external XXL admin executor is disabled. No business bean is mocked.
 * Every valid click fact in these tests originates in event persistence.
 */
@EnabledIfEnvironmentVariable(named = "PULSEFLOW_TEST_DOCKER", matches = "true")
@Testcontainers
@DirtiesContext
@SpringBootTest(classes = PulseFlowApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"pulseflow.events.trusted-source-token=" + MarketingClickPipelineIT.SOURCE_TOKEN,
                "pulseflow.agent.internal-token=" + MarketingClickPipelineIT.AGENT_TOKEN,
                "logging.level.com.pulseflow=INFO", "otel.sdk.disabled=true"})
class MarketingClickPipelineIT {
    static final String SOURCE_TOKEN = "integration-test-event-source-token-32-characters";
    static final String AGENT_TOKEN = "integration-test-agent-token";
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pulseflow_click_test").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=Asia/Shanghai");
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.redis.host", REDIS::getHost);
        r.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockBean XxlJobSpringExecutor externalScheduler;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PerformanceSummaryCalculator summaries;
    @Autowired AttributionService attribution;
    @Autowired AttributionTaskConsumer attributionConsumer;
    @Autowired CompensationJob compensation;
    @Autowired DailyMetricJob daily;
    @Autowired WindowMetricJob windows;
    @Autowired com.pulseflow.mapper.UserBehaviorSummaryMapper behaviorSnapshots;
    @Autowired CampaignSelectionJob selection;
    @Autowired ProfileService profiles;
    @Autowired SqlAudiencePreviewService audiencePreview;
    @Autowired DelayedTaskManager delayedTasks;
    @Autowired ClickEventService clicks;
    @Autowired EventPersistenceService persistence;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired WebQueryService web;
    @Autowired com.pulseflow.job.handler.DataCleanupJob cleanup;
    @Autowired org.redisson.api.RedissonClient redis;
    private static final AtomicLong IDS = new AtomicLong(510000);
    record Fixture(long campaign, long task, long user, LocalDateTime now) {}

    Fixture fixture(boolean sent) {
        long id = IDS.addAndGet(100);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("INSERT INTO campaign (id,name,trigger_type,channel,status) VALUES (?,?,'EVENT','IN_APP','PAUSED')",
                id, "PF_TEST_CLICK_" + id);
        Fixture f = new Fixture(id, id, id + 8000000, now);
        task(f, id, sent);
        return f;
    }

    void task(Fixture f, long taskId, boolean sent) {
        jdbc.update("INSERT INTO delivery_task (id,campaign_id,user_id,dedup_key,channel,status) "
                + "VALUES (?,?,?,?,'IN_APP',?)", taskId, f.campaign, f.user, "click-task-" + taskId,
                sent ? "SENT" : "PROCESSING");
        if (sent) receipt(f, taskId);
    }

    void receipt(Fixture f, long taskId) {
        jdbc.update("UPDATE delivery_task SET status='SENT',sent_at=?,first_send_attempt_at=? WHERE id=?",
                f.now.minusMinutes(10), f.now.minusMinutes(10), taskId);
    }

    String id(Fixture f, String suffix) { return "click-it-" + f.task + "-" + suffix; }

    Map<String,Object> body(String id, long user, String type, LocalDateTime time, Object properties) {
        Map<String,Object> b = new LinkedHashMap<>();
        b.put("eventId", id); b.put("userId", user); b.put("eventType", type);
        b.put("eventTime", time.toString()); b.put("targetId", 2001); b.put("properties", properties);
        return b;
    }

    void post(Map<String,Object> body, String token) {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.set(TrustedEventSource.HEADER, token);
        ResponseEntity<JsonNode> response = http.exchange("/api/events", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("accepted").asBoolean()).isTrue();
    }

    void click(Fixture f, String suffix, LocalDateTime time) {
        post(body(id(f, suffix), f.user, "CLICK", time, Map.of("taskId", f.task, "clickSource", "IN_APP")), SOURCE_TOKEN);
    }

    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    String status(String eventId) {
        List<String> rows = jdbc.queryForList("SELECT marketing_click_status FROM user_event WHERE event_id=?", String.class, eventId);
        return rows.isEmpty() ? "MISSING" : rows.get(0);
    }
    void stored(String eventId, String expected) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(status(eventId)).isEqualTo(expected));
    }
    void barrier(Fixture f) {
        String id = id(f, "barrier-" + UUID.randomUUID());
        // A same-partition fence that does not add to the CLICK metric under assertion.
        post(body(id, f.user, "REMOVE_CART", LocalDateTime.now(), Map.of()), null);
        stored(id, "NONE");
    }

    JsonNode metric(Fixture f, Metric metric) {
        TimeRange range = new TimeRange(f.now.minusDays(1).atOffset(ZoneOffset.ofHours(8)),
                f.now.plusDays(1).atOffset(ZoneOffset.ofHours(8)));
        QueryRequest query = new QueryRequest(metric, range,
                List.of(new Filter(FilterField.CAMPAIGN_ID, Operator.EQ, List.of("" + f.campaign))), List.of(), 10);
        HttpHeaders h = new HttpHeaders(); h.set("X-PulseFlow-Agent-Token", AGENT_TOKEN);
        ResponseEntity<JsonNode> response = http.exchange("/internal/v1/agent-tools/metrics/query", HttpMethod.POST,
                new HttpEntity<>(query, h), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).doesNotContain("userId", "sourceEventId", SOURCE_TOKEN);
        return response.getBody().path("rows").get(0).path("value");
    }

    @Test void httpKafkaClickOrderAttributionSummaryAndAgentCloseTheLoopWithoutSeededClicks() {
        Fixture f = fixture(true);
        long secondTask = IDS.addAndGet(100); task(f, secondTask, true);
        assertThat(summaries.compute(f.campaign).getClickedCount()).isZero();
        LocalDateTime purchase = f.now.minusMinutes(4).minusSeconds(50);
        click(f, "first", purchase.minusSeconds(4));
        Map<String,Object> last = body(id(f,"last"), f.user, "CLICK", purchase.minusSeconds(1), Map.of("taskId",secondTask));
        post(last, SOURCE_TOKEN);
        stored(id(f,"first"), "VALID"); stored(id(f,"last"), "VALID");
        assertThat(summaries.compute(f.campaign).getClickedCount()).isEqualTo(1);
        assertThat(summaries.compute(f.campaign).getClickRate()).isEqualByComparingTo("0.5000");
        Map<String,Object> paid = body(id(f,"order"), f.user, "ORDER_PAID", purchase, Map.of("price",29.9));
        post(paid, SOURCE_TOKEN);
        stored(id(f,"order"), "NONE");
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(400)).untilAsserted(() -> {
            attributionConsumer.processAttributionTasks();
            assertThat(count("SELECT COUNT(*) FROM attribution_record WHERE target_event_id=? AND task_id=?",
                    id(f,"order"), secondTask)).isEqualTo(1);
        });
        var summary = summaries.compute(f.campaign);
        assertThat(summary.getConvertedCount()).isEqualTo(1);
        assertThat(summary.getConversionRate()).isEqualByComparingTo("1.0000");
        assertThat(metric(f, Metric.CLICKS).decimalValue()).isEqualByComparingTo("1");
        assertThat(metric(f, Metric.CTR).decimalValue()).isEqualByComparingTo(summary.getClickRate());
        assertThat(metric(f, Metric.CONVERSION_RATE).decimalValue()).isEqualByComparingTo(summary.getConversionRate());
        // Agent performance is fresh without writing its read-only tool endpoint.
        jdbc.update("UPDATE campaign_performance_summary SET clicked_count=0,click_rate=0 WHERE campaign_id=?",f.campaign);
        HttpHeaders agentHeaders=new HttpHeaders(); agentHeaders.set("X-PulseFlow-Agent-Token",AGENT_TOKEN);
        var performance=http.exchange("/internal/v1/agent-tools/campaigns/"+f.campaign+"/performance",HttpMethod.GET,
                new HttpEntity<>(agentHeaders),JsonNode.class);
        assertThat(performance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(performance.getBody().path("clickedCount").asLong()).isEqualTo(1);
        assertThat(count("SELECT clicked_count FROM campaign_performance_summary WHERE campaign_id=?",f.campaign)).isZero();
        assertThat(web.campaignPerformance(f.campaign).clickedCount()).isEqualTo(1);
        assertThat(web.delivery(secondTask).clicks()).hasSize(1);
        post(last, SOURCE_TOKEN); post(paid, SOURCE_TOKEN); barrier(f);
        assertThat(count("SELECT COUNT(*) FROM click_event WHERE task_id IN (?,?)", f.task,secondTask)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM attribution_record WHERE target_event_id=?",id(f,"order"))).isEqualTo(1);
        assertThat(count("SELECT SUM(event_count) FROM user_metric_hourly WHERE user_id=? AND event_type='CLICK'",f.user)).isEqualTo(2);
    }

    @Test void publicForgedMalformedUnsentAndEarlyClicksStayRawOnly() {
        Fixture f = fixture(true);
        Map<String,Object> forged = body(id(f,"forged"),f.user,"CLICK",f.now,Map.of("taskId",f.task,"marketingTrusted",true));
        forged.put("marketingTrusted",true); post(forged,null); stored(id(f,"forged"),"REJECTED");
        post(forged,SOURCE_TOKEN); // duplicate eventId must never upgrade original trust
        post(body(id(f,"wrong-token"),f.user,"CLICK",f.now,Map.of("taskId",f.task)),"wrong-token");
        post(body(id(f,"ordinary"),f.user,"CLICK",f.now,Map.of()),null);
        post(body(id(f,"bad-id"),f.user,"CLICK",f.now,Map.of("taskId","510000")),SOURCE_TOKEN);
        post(body(id(f,"missing"),f.user,"CLICK",f.now,Map.of("taskId",999999999)),SOURCE_TOKEN);
        post(body(id(f,"early"),f.user,"CLICK",f.now.minusMinutes(11),Map.of("taskId",f.task)),SOURCE_TOKEN);
        // Use a receipt after the effective time; HTTP clock-skew correction must
        // not make an intentionally old client timestamp into a false early click.
        Fixture early = fixture(true);
        jdbc.update("UPDATE delivery_task SET sent_at=? WHERE id=?",early.now.plusSeconds(20),early.task);
        click(early,"before-send",early.now);
        Fixture other = fixture(true);
        post(body(id(f,"other-user"),f.user,"CLICK",f.now,Map.of("taskId",other.task)),SOURCE_TOKEN);
        Fixture failed = fixture(false);
        jdbc.update("UPDATE delivery_task SET status='FAILED' WHERE id=?",failed.task);
        click(failed,"failed",failed.now);
        barrier(f); stored(id(early,"before-send"),"REJECTED"); stored(id(failed,"failed"),"REJECTED");
        assertThat(status(id(f,"ordinary"))).isEqualTo("NONE");
        assertThat(status(id(f,"early"))).isEqualTo("VALID"); // existing clock-skew policy uses effective time
        for (String suffix : List.of("wrong-token","bad-id","missing","other-user")) assertThat(status(id(f,suffix))).isEqualTo("REJECTED");
        assertThat(count("SELECT marketing_trusted FROM user_event WHERE event_id=?",id(f,"forged"))).isZero();
        assertThat(count("SELECT COUNT(*) FROM click_event WHERE source_event_id=?",id(f,"forged"))).isZero();
        assertThat(count("SELECT COUNT(*) FROM click_event WHERE source_event_id=?",id(early,"before-send"))).isZero();
        HttpHeaders h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/api/events",new HttpEntity<>(body(id(f,"bad-properties"),f.user,"CLICK",f.now,List.of(1)),h),String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void deferredClickSurvivesConsumerRestartAndRepeatedCompensation() {
        Fixture f = fixture(false); click(f,"deferred",f.now.minusSeconds(2)); stored(id(f,"deferred"),"PENDING");
        compensation.execute();
        assertThat(count("SELECT retry_count FROM data_compensation_task WHERE event_id=?",id(f,"deferred"))).isEqualTo(1);
        click(f,"deferred",f.now.minusSeconds(2)); barrier(f);
        assertThat(count("SELECT retry_count FROM data_compensation_task WHERE event_id=?",id(f,"deferred"))).isEqualTo(1);
        listeners.stop(); listeners.start();
        receipt(f,f.task);
        jdbc.update("UPDATE data_compensation_task SET next_retry_at=NOW() WHERE event_id=?",id(f,"deferred"));
        compensation.execute(); stored(id(f,"deferred"),"VALID");
        compensation.execute(); clicks.retry(id(f,"deferred"));
        assertThat(count("SELECT COUNT(*) FROM click_event WHERE source_event_id=?",id(f,"deferred"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM data_compensation_task WHERE event_id=?",String.class,id(f,"deferred"))).isEqualTo("DONE");
    }

    @Test void visibilityRetryIsBoundedAndDuplicateHttpDoesNotResetIt() {
        Fixture f = fixture(false); click(f,"never-sent",f.now); stored(id(f,"never-sent"),"PENDING");
        for(int i=0;i<5;i++) {
            jdbc.update("UPDATE data_compensation_task SET next_retry_at=NOW() WHERE event_id=?",id(f,"never-sent"));
            compensation.execute();
        }
        stored(id(f,"never-sent"),"REJECTED");
        click(f,"never-sent",f.now); barrier(f);
        assertThat(count("SELECT retry_count FROM data_compensation_task WHERE event_id=?",id(f,"never-sent"))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM data_compensation_task WHERE event_id=?",String.class,id(f,"never-sent"))).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(*) FROM click_event WHERE source_event_id=?",id(f,"never-sent"))).isZero();
    }

    @Test void kafkaRetriesDatabaseFailureBeyondDefaultBudgetAndRollsBackAllMysqlWrites() throws Exception {
        Fixture f=fixture(true); String event=id(f,"db-retry");
        jdbc.execute("CREATE TRIGGER click_it_failure BEFORE INSERT ON click_event FOR EACH ROW "
                + "BEGIN IF NEW.source_event_id='"+event+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test_write_failure'; END IF; END");
        try {
            click(f,"db-retry",f.now);
            Thread.sleep(12000); // exceed Kafka's previous default retry budget
            assertThat(count("SELECT COUNT(*) FROM user_event WHERE event_id=?",event)).isZero();
            assertThat(count("SELECT COUNT(*) FROM user_metric_hourly WHERE user_id=?",f.user)).isZero();
            assertThat(count("SELECT COUNT(*) FROM click_event WHERE source_event_id=?",event)).isZero();
        } finally { jdbc.execute("DROP TRIGGER click_it_failure"); }
        stored(event,"VALID");
        assertThat(count("SELECT SUM(event_count) FROM user_metric_hourly WHERE user_id=?",f.user)).isEqualTo(1);
    }

    @Test void metricFailureAlsoRollsBackRawEventAndCanBeReplayed() {
        Fixture f=fixture(true); String event=id(f,"metric-rollback");
        Map<String,Object> raw=body(event,f.user,"CLICK",f.now,Map.of("taskId",f.task));
        raw.put("receivedAt",f.now.toString()); raw.put("effectiveEventTime",f.now.toString()); raw.put("marketingTrusted",true);
        jdbc.execute("CREATE TRIGGER metric_it_failure BEFORE INSERT ON user_metric_hourly FOR EACH ROW "
                + "BEGIN IF NEW.user_id="+f.user+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test_metric_failure'; END IF; END");
        try {
            assertThatThrownBy(()->persistence.persist(raw)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("SELECT COUNT(*) FROM user_event WHERE event_id=?",event)).isZero();
        } finally { jdbc.execute("DROP TRIGGER metric_it_failure"); }
        persistence.persist(raw);
        assertThat(status(event)).isEqualTo("VALID");
    }

    @Test void concurrentDuplicateHttpEventsProduceOneFactAndOneMetric() throws Exception {
        Fixture f=fixture(true);
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> pending=new ArrayList<>();
            for(int i=0;i<12;i++) pending.add(pool.submit(()->click(f,"concurrent",f.now)));
            for(Future<?> result:pending) result.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        barrier(f); stored(id(f,"concurrent"),"VALID");
        assertThat(count("SELECT COUNT(*) FROM click_event WHERE source_event_id=?",id(f,"concurrent"))).isEqualTo(1);
        assertThat(count("SELECT SUM(event_count) FROM user_metric_hourly WHERE user_id=? AND event_type='CLICK'",f.user)).isEqualTo(1);
    }

    @Test void legacyInvalidLatestClickCannotHideAnEarlierRealValidClick() {
        Fixture f=fixture(true);
        LocalDateTime purchase=f.now.minusMinutes(4).minusSeconds(50);
        click(f,"real",purchase.minusSeconds(3)); stored(id(f,"real"),"VALID");
        // Deliberately corrupt historical fixture only: no source provenance, and
        // receipt time is later than the click. Its ID must remain untouched.
        long invalidTask=IDS.addAndGet(100); task(f,invalidTask,true);
        jdbc.update("UPDATE delivery_task SET sent_at=? WHERE id=?",f.now.plusMinutes(1),invalidTask);
        jdbc.update("INSERT INTO click_event (user_id,task_id,click_time) VALUES (?,?,?)",f.user,invalidTask,purchase.minusSeconds(1));
        post(body(id(f,"lookup"),f.user,"ORDER_PAID",purchase,Map.of()),SOURCE_TOKEN);
        var mapper=clickMapper;
        assertThat(mapper.selectLastValid(f.user,purchase.minusHours(24),purchase).getSourceEventId()).isEqualTo(id(f,"real"));
        assertThat(mapper.selectLastValid(f.user,f.now.plusSeconds(1),f.now.plusHours(24))).isNull();
        assertThat(mapper.selectLastValid(f.user,f.now.minusSeconds(3),f.now.minusSeconds(3))).isNull();
        assertThat(summaries.compute(f.campaign).getClickedCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(400)).untilAsserted(()->{
            attributionConsumer.processAttributionTasks();
            assertThat(count("SELECT COUNT(*) FROM attribution_record WHERE target_event_id=? AND task_id=?",id(f,"lookup"),f.task)).isEqualTo(1);
        });
    }
    @Autowired com.pulseflow.mapper.ClickEventMapper clickMapper;

    @Test void purchaseWaitsForPendingClickAndLateReceiptIsCompensatedBeforeMatching() {
        Fixture f=fixture(false);
        LocalDateTime purchase=f.now.minusMinutes(4).minusSeconds(50);
        post(body(id(f,"late-order"),f.user,"ORDER_PAID",purchase,Map.of()),SOURCE_TOKEN);
        click(f,"late-click",purchase.minusSeconds(2)); stored(id(f,"late-click"),"PENDING");
        await().atMost(Duration.ofSeconds(15)).until(()->LocalDateTime.now().isAfter(purchase.plusMinutes(5)));
        assertThatThrownBy(()->attribution.executeAttribution(id(f,"late-order")))
                .isInstanceOf(IllegalStateException.class).hasMessage("marketing_click_pending");
        receipt(f,f.task); compensation.execute(); stored(id(f,"late-click"),"VALID");
        assertThat(attribution.executeAttribution(id(f,"late-order"))).isEqualTo(AttributionService.ExecutionResult.MATCHED);
        assertThat(count("SELECT COUNT(*) FROM attribution_record WHERE target_event_id=?",id(f,"late-order"))).isEqualTo(1);
    }

    @Test void compensationDatabaseFailureIsRetryableAndNeverRejectedAsInvalid() {
        Fixture f=fixture(false); String event=id(f,"comp-infra");
        click(f,"comp-infra",f.now); stored(event,"PENDING"); receipt(f,f.task);
        jdbc.execute("CREATE TRIGGER click_comp_failure BEFORE INSERT ON click_event FOR EACH ROW "
                + "BEGIN IF NEW.source_event_id='"+event+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test_comp_failure'; END IF; END");
        try {
            compensation.execute();
            assertThat(status(event)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT last_error FROM data_compensation_task WHERE event_id=?",String.class,event))
                    .isEqualTo("INFRASTRUCTURE_FAILURE");
        } finally { jdbc.execute("DROP TRIGGER click_comp_failure"); }
        jdbc.update("UPDATE data_compensation_task SET next_retry_at=NOW() WHERE event_id=?",event);
        compensation.execute(); stored(event,"VALID");
    }

    @Test void historicalReplayClickOutside24HoursCannotConvert() {
        Fixture f=fixture(true);
        LocalDateTime old=f.now.minusHours(25);
        jdbc.update("UPDATE delivery_task SET sent_at=? WHERE id=?",old.minusMinutes(1),f.task);
        Map<String,Object> raw=body(id(f,"old-click"),f.user,"CLICK",old,Map.of("taskId",f.task));
        raw.put("receivedAt",old.toString()); raw.put("effectiveEventTime",old.toString()); raw.put("marketingTrusted",true);
        persistence.persist(raw); assertThat(status(id(f,"old-click"))).isEqualTo("VALID");
        attribution.onTargetEvent(id(f,"expired-order"),f.user,"ORDER_PAID",f.now.minusMinutes(6));
        attributionConsumer.processAttributionTasks();
        assertThat(jdbc.queryForObject("SELECT status FROM attribution_task WHERE target_event_id=?",String.class,id(f,"expired-order")))
                .isEqualTo("EXPIRED");
        assertThat(count("SELECT COUNT(*) FROM attribution_record WHERE target_event_id=?",id(f,"expired-order"))).isZero();
    }

    @Test void malformedOptionalMetricsDoNotPoisonRawClickPersistence() {
        Fixture f=fixture(true);
        post(body(id(f,"bad-metrics"),f.user,"CLICK",f.now,Map.of("taskId",f.task,"duration",Map.of("bad",true),"price","NaN")),SOURCE_TOKEN);
        stored(id(f,"bad-metrics"),"VALID");
        assertThat(count("SELECT duration_sum FROM user_metric_hourly WHERE user_id=? AND event_type='CLICK'",f.user)).isZero();
    }

    @Test void abandonedAttributionClaimIsRecoveredAfterWorkerCrash() {
        Fixture f=fixture(true); String event=id(f,"abandoned");
        attribution.onTargetEvent(event,f.user,"ORDER_PAID",f.now.minusMinutes(6));
        redis.getScoredSortedSet("delay:attribution",org.redisson.client.codec.StringCodec.INSTANCE).remove(event);
        redis.getScoredSortedSet("delay:attribution:processing",org.redisson.client.codec.StringCodec.INSTANCE).add(System.currentTimeMillis()-360000,event);
        attributionConsumer.processAttributionTasks();
        assertThat(jdbc.queryForObject("SELECT status FROM attribution_task WHERE target_event_id=?",String.class,event)).isEqualTo("EXPIRED");
        assertThat(redis.getScoredSortedSet("delay:attribution:processing",org.redisson.client.codec.StringCodec.INSTANCE).contains(event)).isFalse();
    }

    @Test void reconciliationRotatesPastFirstHundredOwnedPendingTasks() {
        Fixture f=fixture(true);
        for(int i=0;i<101;i++) {
            String event=id(f,"reconcile-"+i);
            attribution.onTargetEvent(event,f.user,"ORDER_PAID",f.now.plusMinutes(10));
        }
        String orphan=id(f,"reconcile-100"); redis.getScoredSortedSet("delay:attribution",org.redisson.client.codec.StringCodec.INSTANCE).remove(orphan);
        for(int i=0;i<4;i++) attribution.reconcilePendingTasks();
        assertThat(redis.getScoredSortedSet("delay:attribution",org.redisson.client.codec.StringCodec.INSTANCE).contains(orphan)).isTrue();
    }

    @Test void exhaustedInfrastructureRetryRemainsAuditableAndCanBeManuallyRecovered() {
        Fixture f=fixture(false); String event=id(f,"exhaust-infra");
        click(f,"exhaust-infra",f.now); stored(event,"PENDING"); receipt(f,f.task);
        jdbc.execute("CREATE TRIGGER click_exhaust_failure BEFORE INSERT ON click_event FOR EACH ROW "
                + "BEGIN IF NEW.source_event_id='"+event+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test_outage'; END IF; END");
        try {
            for(int i=0;i<5;i++) {
                jdbc.update("UPDATE data_compensation_task SET next_retry_at=NOW() WHERE event_id=?",event);
                compensation.execute();
            }
            assertThat(status(event)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM data_compensation_task WHERE event_id=?",String.class,event)).isEqualTo("FAILED");
        } finally { jdbc.execute("DROP TRIGGER click_exhaust_failure"); }
        jdbc.update("UPDATE data_compensation_task SET updated_at=NOW()-INTERVAL 40 DAY WHERE event_id=?",event);
        cleanup.execute();
        assertThat(count("SELECT COUNT(*) FROM data_compensation_task WHERE event_id=?",event)).isEqualTo(1);
        jdbc.update("UPDATE data_compensation_task SET status='PENDING',retry_count=0,next_retry_at=NOW() WHERE event_id=?",event);
        compensation.execute(); stored(event,"VALID");
    }
    @Autowired com.pulseflow.campaign.delivery.DeliveryConsumer deliveryConsumer;
    @Autowired com.pulseflow.campaign.delivery.DeliveryService deliveryService;
    @Autowired com.pulseflow.job.handler.RetryCompensationJob deliveryRetries;
    @Autowired com.pulseflow.job.handler.DispatchRetryJob dispatchRetries;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String,String> kafka;
    @Autowired com.pulseflow.mapper.DeliveryTaskMapper deliveryTasks;
    @Autowired com.pulseflow.campaign.delivery.DeliveryRecoveryService deliveryRecovery;
    @Autowired com.pulseflow.mapper.DataCompensationTaskMapper compensationTasks;

    Fixture pending(String channel) {
        Fixture f=fixture(false);
        jdbc.update("UPDATE delivery_task SET channel=?,status='PENDING',created_at=NOW()-INTERVAL 1 MINUTE WHERE id=?",channel,f.task);
        return f;
    }
    void consume(Fixture f) {
        deliveryConsumer.consume(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "pulseflow.delivery",0,0,""+f.user,"{\"id\":"+f.task+"}"));
    }
    String taskStatus(Fixture f) { return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?",String.class,f.task); }
    LocalDateTime sent(Fixture f) { return deliveryTasks.selectById(f.task).getSentAt(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"IN_APP","PUSH","EMAIL"})
    void actualChannelSendPersistsTaskResultAndDuplicateConsumptionIsHarmless(String channel) throws Exception {
        Fixture f=pending(channel); consume(f);
        assertThat(taskStatus(f)).isEqualTo("SENT"); LocalDateTime original=sent(f);
        assertThat(original).isNotNull();
        for(int i=0;i<4;i++) consume(f);
        var duplicate=kafka.send("pulseflow.delivery",""+f.user,"{\"id\":"+f.task+"}").get(10,TimeUnit.SECONDS);
        var metadata=duplicate.getRecordMetadata();
        var partition=new org.apache.kafka.common.TopicPartition(metadata.topic(),metadata.partition());
        try(var admin=org.apache.kafka.clients.admin.Admin.create(Map.of(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
                var committed=admin.listConsumerGroupOffsets("pulseflow-delivery-group")
                        .partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(partition);
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isGreaterThan(metadata.offset());
            });
        }
        assertThat(sent(f)).isEqualTo(original);
        assertThat(deliveryTasks.selectById(f.task).getFirstSendAttemptAt()).isNotNull();
        if (!"EMAIL".equals(channel)) assertThat(count("SELECT COUNT(*) FROM "+
                ("PUSH".equals(channel)?"push_record":"in_app_message")+" WHERE business_key=?",f.task)).isEqualTo(1);
    }

    @Test void concurrentClaimsUseDatabaseContentAndSendOnlyOnce() throws Exception {
        Fixture f=pending("IN_APP");
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls=new ArrayList<>();
            for(int i=0;i<16;i++) calls.add(pool.submit(()->deliveryConsumer.consume(
                    new org.apache.kafka.clients.consumer.ConsumerRecord<>("pulseflow.delivery",0,0,"x",
                            "{\"id\":"+f.task+",\"userId\":999,\"channel\":\"EMAIL\"}"))));
            for(var call:calls) call.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(taskStatus(f)).isEqualTo("SENT");
        assertThat(count("SELECT COUNT(*) FROM in_app_message WHERE business_key=? AND user_id=?",f.task,f.user)).isEqualTo(1);
        assertThat(redis.getBucket("freq:user:"+f.user+":"+java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),org.redisson.client.codec.StringCodec.INSTANCE).get()).isEqualTo("1");
    }

    @Test void frequencyRejectionAndCancellationNeverBecomeSendAttempts() {
        Fixture denied=pending("IN_APP");
        jdbc.update("UPDATE campaign SET user_daily_limit=0 WHERE id=?",denied.campaign); consume(denied);
        assertThat(taskStatus(denied)).isEqualTo("CANCELLED");
        assertThat(deliveryTasks.selectById(denied.task).getFirstSendAttemptAt()).isNull();
        Fixture cancelled=pending("PUSH");
        jdbc.update("UPDATE delivery_task SET status='CANCELLED' WHERE id=?",cancelled.task); consume(cancelled);
        assertThat(count("SELECT COUNT(*) FROM push_record WHERE business_key=?",cancelled.task)).isZero();
        assertThat(summaries.compute(denied.campaign).getSentCount()).isZero();
    }

    @Test void failedChannelAttemptRetriesThroughSchedulerAndRealKafkaWithoutDoubleQuota() {
        Fixture f=pending("IN_APP");
        jdbc.execute("CREATE TRIGGER send_failure BEFORE INSERT ON in_app_message FOR EACH ROW BEGIN IF NEW.business_key="
                +f.task+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='channel_unavailable'; END IF; END");
        try { consume(f); } finally { jdbc.execute("DROP TRIGGER send_failure"); }
        assertThat(taskStatus(f)).isEqualTo("WAIT_RETRY");
        assertThat(sent(f)).isNull();
        assertThat(summaries.compute(f.campaign).getSentCount()).isEqualTo(1);
        assertThat(summaries.compute(f.campaign).getDeliveredCount()).isZero();
        assertThat(metric(f,Metric.SENT).asLong()).isEqualTo(1);
        jdbc.update("UPDATE delivery_task SET next_retry_at=NOW() WHERE id=?",f.task);
        deliveryRetries.execute(); dispatchRetries.execute();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(taskStatus(f)).isEqualTo("SENT"));
        assertThat(count("SELECT retry_count FROM delivery_task WHERE id=?",f.task)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM in_app_message WHERE business_key=?",f.task)).isEqualTo(1);
        assertThat(metric(f,Metric.DELIVERED).asLong()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"IN_APP","PUSH"})
    void taskWriteFailureRecoversOriginalChannelTimeBeforeQuotaAndCompensatesRealClick(String channel) {
        Fixture f=pending(channel);
        jdbc.execute("CREATE TRIGGER task_write_failure BEFORE UPDATE ON delivery_task FOR EACH ROW BEGIN IF NEW.id="
                +f.task+" AND NEW.status='SENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='task_write_failure'; END IF; END");
        try { consume(f); } finally { jdbc.execute("DROP TRIGGER task_write_failure"); }
        assertThat(taskStatus(f)).isEqualTo("WAIT_RETRY");
        LocalDateTime original=jdbc.queryForObject("SELECT "+("PUSH".equals(channel)?"pushed_at FROM push_record":"created_at FROM in_app_message")
                +" WHERE business_key=?",LocalDateTime.class,f.task);
        click(f,"recovery-click",LocalDateTime.now()); stored(id(f,"recovery-click"),"PENDING");
        // Recovery must win even at exhausted budget and with quota denied.
        jdbc.update("UPDATE delivery_task SET retry_count=5 WHERE id=?",f.task);
        jdbc.update("UPDATE campaign SET user_daily_limit=0 WHERE id=?",f.campaign);
        deliveryRetries.execute();
        assertThat(taskStatus(f)).isEqualTo("SENT");
        assertThat(sent(f)).isEqualTo(original);
        compensation.execute(); stored(id(f,"recovery-click"),"VALID");
        consume(f); assertThat(sent(f)).isEqualTo(original);
        assertThat(count("SELECT COUNT(*) FROM "+("PUSH".equals(channel)?"push_record":"in_app_message")
                +" WHERE business_key=?",f.task)).isEqualTo(1);
    }

    @Test void crashAfterChannelCommitLeavesProcessingAndTimeoutRecoveryDoesNotSendAgain() {
        Fixture f=pending("IN_APP");
        jdbc.execute("CREATE TRIGGER task_crash BEFORE UPDATE ON delivery_task FOR EACH ROW BEGIN IF NEW.id="
                +f.task+" AND NEW.status IN ('SENT','WAIT_RETRY') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='crashed'; END IF; END");
        try { assertThatThrownBy(()->consume(f)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("DROP TRIGGER task_crash"); }
        assertThat(taskStatus(f)).isEqualTo("PROCESSING");
        LocalDateTime original=jdbc.queryForObject("SELECT created_at FROM in_app_message WHERE business_key=?",LocalDateTime.class,f.task);
        jdbc.update("UPDATE delivery_task SET processing_at=NOW()-INTERVAL 6 MINUTE WHERE id=?",f.task);
        deliveryRetries.execute();
        assertThat(sent(f)).isEqualTo(original);
        assertThat(count("SELECT COUNT(*) FROM in_app_message WHERE business_key=?",f.task)).isEqualTo(1);
    }

    @Test void abandonedClaimWithoutSendGetsDueRetryInsteadOfBeingStuckWithNullRetryTime() {
        Fixture f=pending("PUSH");
        assertThat(deliveryTasks.tryClaim(f.task)).isEqualTo(1);
        jdbc.update("UPDATE delivery_task SET processing_at=NOW()-INTERVAL 6 MINUTE WHERE id=?",f.task);
        deliveryRetries.execute(); assertThat(taskStatus(f)).isEqualTo("WAIT_RETRY");
        assertThat(deliveryTasks.selectById(f.task).getNextRetryAt()).isNotNull();
        deliveryRetries.execute(); dispatchRetries.execute();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(taskStatus(f)).isEqualTo("SENT"));
    }

    @Test void emailUnknownOutcomeStopsAutomaticResendAndNeverInventsSuccessTime() {
        Fixture f=pending("EMAIL");
        jdbc.execute("CREATE TRIGGER email_write_failure BEFORE UPDATE ON delivery_task FOR EACH ROW BEGIN IF NEW.id="
                +f.task+" AND NEW.status='SENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='lost_email_ack'; END IF; END");
        try { consume(f); } finally { jdbc.execute("DROP TRIGGER email_write_failure"); }
        assertThat(taskStatus(f)).isEqualTo("FAILED");
        assertThat(sent(f)).isNull();
        LocalDateTime attempt=deliveryTasks.selectById(f.task).getFirstSendAttemptAt();
        jdbc.update("UPDATE delivery_task SET status='WAIT_RETRY',next_retry_at=NOW() WHERE id=?",f.task);
        deliveryRetries.execute(); consume(f);
        assertThat(taskStatus(f)).isEqualTo("FAILED");
        assertThat(deliveryTasks.selectById(f.task).getLastError()).isEqualTo("EMAIL_SEND_OUTCOME_UNKNOWN");
        assertThat(deliveryTasks.selectById(f.task).getFirstSendAttemptAt()).isEqualTo(attempt);
        assertThat(metric(f,Metric.SENT).asLong()).isEqualTo(1);
        assertThat(metric(f,Metric.DELIVERED).asLong()).isZero();
    }

    @Test void creationDedupAndCommittedOutboxPublishRealTasks() {
        Fixture f=fixture(false);
        var dto=com.pulseflow.common.dto.DeliveryTaskDto.builder().campaignId(f.campaign).userId(f.user)
                .channel("PUSH").dedupKey("actual-service-"+f.task).messageContent("hello").build();
        deliveryService.createDeliveryTask(dto); deliveryService.createDeliveryTask(dto);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(count(
                "SELECT COUNT(*) FROM delivery_task WHERE dedup_key=? AND status='SENT' AND dispatch_status='PUBLISHED'",
                dto.getDedupKey())).isEqualTo(1));
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE dedup_key=?",dto.getDedupKey())).isEqualTo(1);
    }

    /** No successful task or marketing click is seeded: includes the real five-minute grace. */
    @Test void decisionSendClickPurchaseSummaryAndAgentCompleteTheEntireRealPipeline() {
        Fixture f=fixture(false);
        jdbc.update("DELETE FROM delivery_task WHERE id=?",f.task);
        jdbc.update("UPDATE campaign SET status='ACTIVE',event_types='CONTENT_VIEW',user_daily_limit=10,"
                +"campaign_weekly_limit=10 WHERE id=?",f.campaign);
        try {
            String trigger=id(f,"decision");
            post(body(trigger,f.user,"CONTENT_VIEW",LocalDateTime.now(),Map.of()),SOURCE_TOKEN);
            await().atMost(Duration.ofSeconds(40)).untilAsserted(()->assertThat(count(
                    "SELECT COUNT(*) FROM delivery_task WHERE trigger_event_id=? AND status='SENT' "
                            +"AND dispatch_status='PUBLISHED' AND sent_at IS NOT NULL",trigger)).isEqualTo(1));
            long task=jdbc.queryForObject("SELECT id FROM delivery_task WHERE trigger_event_id=?",Long.class,trigger);
            Fixture actual=new Fixture(f.campaign,task,f.user,LocalDateTime.now());
            click(actual,"full-click",LocalDateTime.now()); stored(id(actual,"full-click"),"VALID");
            post(body(id(actual,"full-order"),f.user,"ORDER_PAID",LocalDateTime.now(),Map.of("price",19.9)),SOURCE_TOKEN);
            stored(id(actual,"full-order"),"NONE");
            await().atMost(Duration.ofMinutes(6)).pollInterval(Duration.ofSeconds(2)).untilAsserted(()->{
                attributionConsumer.processAttributionTasks();
                assertThat(count("SELECT COUNT(*) FROM attribution_record WHERE target_event_id=? AND task_id=?",
                        id(actual,"full-order"),task)).isEqualTo(1);
            });
            var summary=summaries.compute(f.campaign);
            assertThat(summary.getSentCount()).isEqualTo(1);
            assertThat(summary.getDeliveredCount()).isEqualTo(1);
            assertThat(summary.getClickedCount()).isEqualTo(1);
            assertThat(summary.getConvertedCount()).isEqualTo(1);
            for(var m:List.of(Metric.SENT,Metric.DELIVERED,Metric.CLICKS,Metric.CONVERSIONS,
                    Metric.ATTRIBUTED_CONVERSIONS,Metric.CTR,Metric.CONVERSION_RATE))
                assertThat(metric(actual,m).decimalValue()).isEqualByComparingTo("1");
            assertThat(web.delivery(task).record().id()).isNull();
            assertThat(web.delivery(task).record().sentAt()).isEqualTo(deliveryTasks.selectById(task).getSentAt());
            assertThat(web.campaignPerformance(f.campaign).convertedCount()).isEqualTo(1);
        } finally { jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?",f.campaign); }
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void rolledBackCreationCannotPublishAnUncommittedTask() {
        Fixture f=fixture(false);
        String key="rolled-back-delivery-"+f.task;
        var dto=com.pulseflow.common.dto.DeliveryTaskDto.builder().campaignId(f.campaign).userId(f.user)
                .channel("IN_APP").dedupKey(key).messageContent("rolled back").build();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            deliveryService.createDeliveryTask(dto);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE dedup_key=?",key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM in_app_message WHERE campaign_id=?",f.campaign)).isZero();
    }

    @Test void unknownChannelFailsWithoutAnAttemptOrReceipt() {
        Fixture f=pending("UNKNOWN"); consume(f);
        assertThat(taskStatus(f)).isEqualTo("FAILED");
        assertThat(deliveryTasks.selectById(f.task).getFirstSendAttemptAt()).isNull();
        assertThat(sent(f)).isNull();
    }

    @Test void expiredClaimCannotOverwriteLaterSuccess() {
        Fixture f=pending("IN_APP");
        assertThat(deliveryTasks.tryClaim(f.task)).isEqualTo(1);
        LocalDateTime oldClaim=deliveryTasks.selectById(f.task).getProcessingAt();
        jdbc.update("UPDATE delivery_task SET status='PENDING' WHERE id=?",f.task);
        consume(f);
        LocalDateTime original=sent(f);
        assertThat(deliveryTasks.finishAttempt(f.task,oldClaim,"WAIT_RETRY",LocalDateTime.now(),"stale",1)).isZero();
        assertThat(sent(f)).isEqualTo(original);
        assertThat(taskStatus(f)).isEqualTo("SENT");
    }

    @Test void brokerAckWriteFailureIsRedispatchedEvenAfterTheTaskWasSent() {
        Fixture f=fixture(false); String key="ack-recovery-"+f.task;
        jdbc.execute("CREATE TRIGGER dispatch_ack_failure BEFORE UPDATE ON delivery_task FOR EACH ROW BEGIN IF NEW.dedup_key='"
                +key+"' AND NEW.dispatch_status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='lost_broker_ack_write'; END IF; END");
        try {
            deliveryService.createDeliveryTask(com.pulseflow.common.dto.DeliveryTaskDto.builder().campaignId(f.campaign)
                    .userId(f.user).channel("IN_APP").dedupKey(key).build());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(count(
                    "SELECT COUNT(*) FROM delivery_task WHERE dedup_key=? AND status='SENT' AND dispatch_status='PENDING'",key)).isEqualTo(1));
        } finally { jdbc.execute("DROP TRIGGER dispatch_ack_failure"); }
        long task=jdbc.queryForObject("SELECT id FROM delivery_task WHERE dedup_key=?",Long.class,key);
        LocalDateTime original=deliveryTasks.selectById(task).getSentAt();
        jdbc.update("UPDATE delivery_task SET created_at=NOW()-INTERVAL 1 MINUTE WHERE id=?",task);
        dispatchRetries.execute();
        assertThat(deliveryTasks.selectById(task).getDispatchStatus()).isEqualTo("PUBLISHED");
        assertThat(deliveryTasks.selectById(task).getSentAt()).isEqualTo(original);
        assertThat(count("SELECT COUNT(*) FROM in_app_message WHERE business_key=?",task)).isEqualTo(1);
    }

    @Test void legacyProcessingWithoutClaimTimeIsRecoveredThroughTheExistingJobs() {
        Fixture f=pending("IN_APP");
        jdbc.update("UPDATE delivery_task SET status='PROCESSING',processing_at=NULL WHERE id=?",f.task);
        deliveryRetries.execute(); assertThat(taskStatus(f)).isEqualTo("WAIT_RETRY");
        deliveryRetries.execute(); dispatchRetries.execute();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(taskStatus(f)).isEqualTo("SENT"));
    }

    @Test void channelOwnershipConflictFailsClosedAndDoesNotBlockOtherRecoveries() {
        Fixture bad=pending("IN_APP");
        jdbc.update("INSERT INTO in_app_message(business_key,user_id,campaign_id) VALUES(?,?,?)",bad.task,bad.user+1,bad.campaign);
        jdbc.update("UPDATE delivery_task SET status='WAIT_RETRY',next_retry_at=NOW() WHERE id=?",bad.task);
        Fixture good=pending("PUSH");
        jdbc.update("UPDATE delivery_task SET status='WAIT_RETRY',next_retry_at=NOW() WHERE id=?",good.task);
        deliveryRetries.execute();
        assertThat(taskStatus(bad)).isEqualTo("FAILED");
        assertThat(sent(bad)).isNull();
        assertThat(deliveryTasks.selectById(bad.task).getLastError()).isEqualTo("CHANNEL_RECEIPT_MISMATCH");
        dispatchRetries.execute();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(taskStatus(good)).isEqualTo("SENT"));
    }


    @Test
    void calendarSnapshotsUseDistinctDaysAndStablePaidOrdersAcrossBoundaries() {
        long user = IDS.incrementAndGet() + 9_000_000;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        BehaviorWindow window = new BehaviorWindow(now.toLocalDate());
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        var oldCache = redis.<String, Object>getMap("user:window:" + user);
        oldCache.put("active_7d", 99);
        jdbc.update("INSERT INTO user_behavior_summary (user_id,metric_type,metric_value,calculated_at) "
                + "VALUES (?,'active_7d',99,?)", user, now);

        fact(user, "old-view", "CONTENT_VIEW", window.activityStart().minusNanos(1_000_000), Map.of());
        fact(user, "first-day", "ADD_CART", window.activityStart(), Map.of("cartItemId", "sku"));
        fact(user, "same-day", "CONTENT_VIEW", window.activityStart().plusHours(2), Map.of());
        fact(user, "legacy", "SEARCH", today.minusDays(4), Map.of());
        fact(user, "click", "CLICK", today.minusDays(2), Map.of());
        fact(user, "unrolled", "CONTENT_VIEW", today.minusDays(3).plusHours(11), Map.of());
        fact(user, "today", "CONTENT_VIEW", now, Map.of());
        fact(user, "tomorrow", "ADD_CART", window.endExclusive(), Map.of());
        fact(user, "old-order", "ORDER_PAID", window.purchaseStart().minusNanos(1_000_000), Map.of("orderId", "old"));
        fact(user, "old-order-replay", "ORDER_PAID", today, Map.of("orderId", "old"));
        fact(user, "first-paid", "ORDER_PAID", window.purchaseStart(),
                Map.of("orderId", "one", "price", 6_000_000_000L));
        fact(user, "first-paid-replay", "ORDER_PAID", today,
                Map.of("orderId", "one", "price", 6_000_000_000L));
        fact(user, "no-order-id", "ORDER_PAID", today, Map.of());
        fact(user, "no-order-id", "ORDER_PAID", today, Map.of());
        fact(user, "future-order", "ORDER_PAID", window.endExclusive(), Map.of("orderId", "future"));

        assertThat(behaviorSnapshots.readyForRecall(window.activityStart(), today, today.minusNanos(1),
                window.endExclusive())).isFalse();
        LocalDateTime firstCalculation = now.equals(today) ? now : now.minusSeconds(1);
        windows.calculate(firstCalculation);
        assertThat(behaviorSnapshots.readyForRecall(window.activityStart(), today, now, window.endExclusive())).isTrue();
        assertThat(profiles.getWindowMetrics(user)).containsEntry("active_days_7d", new BigDecimal("3.00"))
                .containsEntry("order_count_30d", new BigDecimal("2.00"))
                .containsEntry("spend_30d", new BigDecimal("12000000000.00"))
                .doesNotContainKey("active_7d");
        daily.execute();
        for (int day : List.of(1, 2, 4, 5)) {
            fact(user, "extra-active-" + day, "ADD_CART", today.minusDays(day), Map.of());
        }
        windows.calculate(now);
        assertThat(profiles.getWindowMetrics(user)).containsEntry("active_days_7d", new BigDecimal("7.00"));
        assertThat(profiles.readCurrentWindowMetrics(List.of(user), window.endExclusive())).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM user_behavior_summary WHERE user_id=? AND metric_type='active_7d'", user))
                .isEqualTo(1);
    }

    @Test
    void scheduledPreviewAndExecutionRecheckPaidFactsAndRecoverTheSamePagedExecution() {
        long campaign = IDS.addAndGet(100);
        long selected = 10_000_000 + campaign;
        long buyer = selected + 1;
        long disabled = selected + 2;
        long insufficient = selected + 3;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime due = now.minusSeconds(10);

        List<Object[]> accounts = new ArrayList<>();
        for (int i = 1; i <= 510; i++) accounts.add(new Object[]{selected - i, 1});
        accounts.addAll(List.of(new Object[]{selected, 1}, new Object[]{buyer, 1},
                new Object[]{disabled, 0}, new Object[]{insufficient, 1}));
        jdbc.batchUpdate("INSERT INTO user_profile(user_id,status) VALUES (?,?)", accounts);
        for (long user : List.of(selected, buyer, disabled, insufficient)) {
            fact(user, "today", "ADD_CART", now, Map.of());
            if (user != insufficient) fact(user, "yesterday", "CONTENT_VIEW", now.minusDays(1), Map.of());
        }
        windows.calculate(now);
        assertThat(profiles.getWindowMetrics(buyer)).containsEntry("order_count_30d", BigDecimal.ZERO);
        post(body(buyer + "-paid", buyer, "ORDER_PAID", LocalDateTime.now(), Map.of("orderId", "free", "price", 0)), null);
        stored(buyer + "-paid", "NONE");

        CampaignDsl dsl = CampaignDsl.builder().audience(AudienceGroup.builder().logic("AND")
                .conditions(List.of(AudienceCondition.builder().field("activeDays7d").operator("GTE")
                                .valueType("INTEGER").value(2).build(),
                        AudienceCondition.builder().field("orderCount30d").operator("EQ")
                                .valueType("INTEGER").value(0).build()))
                .build()).build();
        assertThat(audiencePreview.enabledCandidateIds()).doesNotContain(disabled);
        assertThat(audiencePreview.match(dsl, List.of(selected, buyer, insufficient), now.plusSeconds(1)))
                .containsExactly(selected);

        jdbc.update("INSERT INTO campaign(id,name,trigger_type,channel,message_template,status,next_trigger_at) "
                + "VALUES (?,?,'SCHEDULED','IN_APP','召回优惠','ACTIVE',?)", campaign, "PF_TEST_RECALL_" + campaign, due);
        jdbc.update("INSERT INTO campaign_rule(campaign_id,rule_name,rule_type,rule_config) "
                + "VALUES (?,'legacy active alias','PROFILE','{\"metricType\":\"active_7d\",\"operator\":\"GTE\",\"threshold\":2}')", campaign);

        jdbc.execute("CREATE TRIGGER it_reject_execution BEFORE INSERT ON campaign_execution "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='simulated instance insert failure'");
        try {
            selection.execute();
            assertThat(jdbc.queryForObject("SELECT next_trigger_at FROM campaign WHERE id=?", LocalDateTime.class, campaign))
                    .isEqualTo(due);
            assertThat(count("SELECT COUNT(*) FROM campaign_execution WHERE campaign_id=?", campaign)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER it_reject_execution");
        }

        CompletableFuture.allOf(CompletableFuture.runAsync(selection::execute),
                CompletableFuture.runAsync(selection::execute)).orTimeout(25, TimeUnit.SECONDS).join();
        long execution = count("SELECT id FROM campaign_execution WHERE campaign_id=?", campaign);
        assertThat(jdbc.queryForList("SELECT user_id FROM delivery_task WHERE campaign_id=?", Long.class, campaign))
                .containsExactly(selected);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=? AND status='SENT'", campaign)).isEqualTo(1));
        jdbc.update("UPDATE campaign_execution SET status='RUNNING', started_at=? WHERE id=?", now.minusMinutes(11), execution);
        selection.execute();
        selection.execute();
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign_execution WHERE id=? AND status='DONE' AND retry_count=1", execution))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE id=? AND next_trigger_at IS NULL", campaign)).isEqualTo(1);
        jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?", campaign);
    }

    @Test
    void delayedCartReminderSendsOnceAndCancelsForRemoveOrCommittedPayment() {
        long campaign = IDS.addAndGet(100);
        jdbc.update("INSERT INTO campaign(id,name,trigger_type,event_types,delay_seconds,channel,message_template,status) "
                + "VALUES (?,?,'DELAYED','ADD_CART',60,'PUSH','加购优惠','ACTIVE')", campaign, "PF_TEST_CART_" + campaign);
        long sends = campaign + 11_000_000;
        long removed = sends + 1;
        long paid = sends + 2;
        long paymentPendingRedis = sends + 3;
        String item = "sku-reminder";
        String type = "DELAYED_CAMPAIGN";
        var pending = redis.<String>getScoredSortedSet("delay:pending:" + type);
        var processing = redis.<String>getScoredSortedSet("delay:processing:" + type);
        for (long user : List.of(sends, removed, paid, paymentPendingRedis)) {
            String eventId = user + "-add";
            Map<String, Object> add = body(eventId, user, "ADD_CART", LocalDateTime.now(), Map.of("cartItemId", item));
            post(add, null);
            post(add, null);
            String task = DedupKeyUtil.forDelayed(campaign, user, item, eventId);
            await().atMost(Duration.ofSeconds(30)).until(() -> pending.contains(task));
        }
        post(body(removed + "-remove", removed, "REMOVE_CART", LocalDateTime.now(), Map.of("cartItemId", item)), null);
        var removedCart = redis.<String, String>getMap("user:cart:" + removed, StringCodec.INSTANCE);
        await().atMost(Duration.ofSeconds(30)).until(() -> !removedCart.containsKey(item));

        post(body(paid + "-paid", paid, "ORDER_PAID", LocalDateTime.now(), Map.of("cartItemId", item, "orderId", "paid")), null);
        var paidCart = redis.<String, String>getMap("user:cart:" + paid, StringCodec.INSTANCE);
        await().atMost(Duration.ofSeconds(30)).until(() -> !paidCart.containsKey(item));

        // Persisted payment with Redis Phase 2 still outstanding.
        fact(paymentPendingRedis, "paid", "ORDER_PAID", LocalDateTime.now(), Map.of("cartItemId", item, "orderId", "paid"));
        assertThat(redis.<String, String>getMap("user:cart:" + paymentPendingRedis, StringCodec.INSTANCE)
                .containsKey(item)).isTrue();
        for (long user : List.of(sends, removed, paid, paymentPendingRedis)) {
            String task = DedupKeyUtil.forDelayed(campaign, user, item, user + "-add");
            pending.add(System.currentTimeMillis() - 1, task);
            await().atMost(Duration.ofSeconds(30)).until(() -> !pending.contains(task) && !processing.contains(task));
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT user_id FROM delivery_task WHERE campaign_id=? AND status='SENT'",
                        Long.class, campaign)).containsExactly(sends));
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isEqualTo(1);
        jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?", campaign);
    }

    @Test
    void delayedQueuePreservesLegacyMembersAndFencesExpiredClaims() {
        String type = "DELAYED_LEASE_IT";
        String task = "1:2:sku:original-add";
        long old = System.currentTimeMillis() - Duration.ofMinutes(6).toMillis();
        var pending = redis.<String>getScoredSortedSet("delay:pending:" + type);
        var processing = redis.<String>getScoredSortedSet("delay:processing:" + type);
        pending.add(old, task);
        delayedTasks.addDelayedTask(type, task, old + 500);
        var blockedClaim = redis.getBucket("delay:processing:" + type, StringCodec.INSTANCE);
        blockedClaim.set("wrong type");
        try {
            assertThatThrownBy(() -> delayedTasks.claimTasks(type, old, 50))
                    .isInstanceOf(org.redisson.client.RedisException.class);
            assertThat(pending.contains(task)).isTrue();
        } finally {
            blockedClaim.delete();
        }
        assertThat(delayedTasks.claimTasks(type, old, 50)).containsExactly(task);
        delayedTasks.addDelayedTask(type, task, old);
        assertThat(delayedTasks.claimTasks(type, old, 50)).isEmpty();
        var blockedRecovery = redis.getBucket("delay:pending:" + type, StringCodec.INSTANCE);
        blockedRecovery.set("wrong type");
        try {
            assertThatThrownBy(() -> delayedTasks.recoverStuckTasks(type, Duration.ofMinutes(5).toMillis()))
                    .isInstanceOf(org.redisson.client.RedisException.class);
            assertThat(processing.contains(task)).isTrue();
        } finally {
            blockedRecovery.delete();
        }
        assertThat(delayedTasks.recoverStuckTasks(type, Duration.ofMinutes(5).toMillis())).containsExactly(task);
        long next = System.currentTimeMillis() + 100;
        assertThat(delayedTasks.claimTasks(type, next, 50)).containsExactly(task);
        delayedTasks.completeTask(type, task, old);
        assertThat(processing.contains(task)).isTrue();
        delayedTasks.completeTask(type, task, next);
        assertThat(processing.contains(task)).isFalse();
    }

    @Test
    void eventCompensationReplaysAfterRedisFailureAndKeepsRuleAndDeliveryIdempotency() {
        long campaign = IDS.addAndGet(100);
        long user = campaign + 12_000_000;
        LocalDateTime time = LocalDateTime.now();
        jdbc.update("INSERT INTO campaign(id,name,trigger_type,event_types,channel,message_template,status) "
                + "VALUES (?,?,'EVENT','CONTENT_VIEW','IN_APP','浏览优惠','ACTIVE')", campaign, "PF_TEST_VIEW_" + campaign);
        jdbc.update("INSERT INTO campaign_rule(campaign_id,rule_name,rule_type,rule_config) "
                + "VALUES (?,'category','EVENT','{\"propertyKey\":\"category\",\"propertyValue\":\"it-category\"}')", campaign);
        post(body(user + "-no-match", user, "CONTENT_VIEW", time, Map.of("category", "other")), null);
        stored(user + "-no-match", "NONE");
        String day = time.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String dailyKey = "user:daily:" + user + ":" + day;
        await().atMost(Duration.ofSeconds(30)).until(() ->
                "1".equals(redis.<String, String>getMap(dailyKey, StringCodec.INSTANCE).get("views")));
        redis.getKeys().delete(dailyKey);
        var wrongType = redis.<String>getBucket(dailyKey, StringCodec.INSTANCE);
        wrongType.set("simulated WRONGTYPE");
        Map<String, Object> matching = body(user + "-match", user, "CONTENT_VIEW", time, Map.of("category", "it-category"));
        try {
            post(matching, null);
            await().atMost(Duration.ofSeconds(30)).until(() -> count(
                    "SELECT COUNT(*) FROM data_compensation_task WHERE event_id=? AND status='PENDING'", user + "-match") == 1);
            assertThat(status(user + "-match")).isEqualTo("NONE");
            assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isZero();
        } finally {
            wrongType.delete();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            compensation.execute();
            assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=? AND status='SENT'", campaign)).isEqualTo(1);
        });
        post(matching, null);
        String barrier = user + "-barrier";
        post(body(barrier, user, "CLICK", time, Map.of()), null);
        stored(barrier, "NONE");
        assertThat(redis.<String, String>getMap(dailyKey, StringCodec.INSTANCE).get("views")).isEqualTo("1");
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isEqualTo(1);
        jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?", campaign);
    }


    @Test
    void staleCartAddsCannotResurrectRemovedItemsAndCanonicalKeysSupportDelimiters() {
        long campaign = IDS.addAndGet(100);
        long user = 13_000_000 + campaign;
        String item = "catalog:sku:" + "x".repeat(125);
        String removeId = "review-remove-" + user;
        String oldAddId = "review:old-add:" + user;
        String newAddId = "review:new-add:" + user;
        LocalDateTime time = LocalDateTime.now().withNano(0);
        var cart = redis.<String, String>getMap("user:cart:" + user, StringCodec.INSTANCE);
        var pending = redis.<String>getScoredSortedSet("delay:pending:DELAYED_CAMPAIGN");
        var processing = redis.<String>getScoredSortedSet("delay:processing:DELAYED_CAMPAIGN");
        jdbc.update("INSERT INTO campaign(id,name,trigger_type,event_types,delay_seconds,channel,message_template,status) "
                + "VALUES (?,?,'DELAYED','ADD_CART',60,'PUSH','加购优惠','ACTIVE')", campaign, "PF_REVIEW_CART_" + campaign);
        try {
            post(body(removeId, user, "REMOVE_CART", time, Map.of("cartItemId", item)), null);
            post(body(oldAddId, user, "ADD_CART", time, Map.of("cartItemId", item)), null);
            String oldKey = DedupKeyUtil.forDelayed(campaign, user, item, oldAddId);
            await().atMost(Duration.ofSeconds(20)).until(() -> pending.contains(oldKey));
            assertThat(cart.containsKey(item)).isFalse();

            // An obsolete Redis value must still lose to the committed cancellation at insert time.
            cart.put(item, "{}");
            pending.add(System.currentTimeMillis() - 1, oldKey);
            await().atMost(Duration.ofSeconds(15)).until(() -> !pending.contains(oldKey) && !processing.contains(oldKey));
            assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isZero();

            post(body(newAddId, user, "ADD_CART", time.plusSeconds(1), Map.of("cartItemId", item)), null);
            String newKey = DedupKeyUtil.forDelayed(campaign, user, item, newAddId);
            await().atMost(Duration.ofSeconds(20)).until(() -> pending.contains(newKey));
            pending.add(System.currentTimeMillis() - 1, newKey);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE dedup_key=? AND status='SENT'", newKey)).isEqualTo(1));
            assertThat(jdbc.queryForObject("SELECT trigger_event_id FROM delivery_task WHERE dedup_key=?", String.class, newKey))
                    .isEqualTo(newAddId);
        } finally {
            jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?", campaign);
        }
    }

    @Test
    void earlierPaidFactCanResetAnExistingSameDaySnapshotToZero() {
        long user = 14_000_000 + IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        BehaviorWindow window = new BehaviorWindow(now.toLocalDate());
        fact(user, "reported", "ORDER_PAID", now, Map.of("orderId", "one", "price", 0));
        windows.calculate(now);
        assertThat(profiles.getWindowMetrics(user)).containsEntry("order_count_30d", new BigDecimal("1.00"));
        fact(user, "earlier", "ORDER_PAID", window.purchaseStart().minusSeconds(1), Map.of("orderId", "one", "price", 0));
        windows.calculate(now);
        assertThat(profiles.getWindowMetrics(user)).containsEntry("order_count_30d", new BigDecimal("0.00"));
        assertThat(count("SELECT COUNT(*) FROM attribution_task WHERE user_id=?", user)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM attribution_task WHERE target_event_id=?", user + "-reported")).isEqualTo(1);
    }

    @Test
    void leaseExpiryAndReclaimFenceMaterializationAndDisabledAccountsAreRechecked() {
        long campaign = IDS.addAndGet(100);
        long user = 15_000_000 + campaign;
        jdbc.update("INSERT INTO user_profile(user_id,status) VALUES (?,1)", user);
        jdbc.update("INSERT INTO campaign(id,name,trigger_type,channel,status) VALUES (?,?,'SCHEDULED','IN_APP','ACTIVE')",
                campaign, "PF_REVIEW_LEASE_" + campaign);
        jdbc.update("INSERT INTO campaign_execution(campaign_id,scheduled_at,status,started_at,retry_count) "
                + "VALUES (?,NOW(),'RUNNING',?,0)", campaign, LocalDateTime.now().minusMinutes(11));
        long execution = count("SELECT id FROM campaign_execution WHERE campaign_id=?", campaign);
        var scheduled = com.pulseflow.entity.Campaign.builder().id(campaign).build();
        var attempt = com.pulseflow.entity.CampaignExecution.builder().id(execution).retryCount(0).build();
        var window = new BehaviorWindow(LocalDate.now());
        assertThat(deliveryService.createScheduledPage(scheduled, attempt, List.of(user), window)).isFalse();
        jdbc.update("UPDATE campaign_execution SET retry_count=1,started_at=? WHERE id=?", LocalDateTime.now(), execution);
        assertThat(deliveryService.createScheduledPage(scheduled, attempt, List.of(user), window)).isFalse();
        attempt.setRetryCount(1);
        jdbc.update("UPDATE user_profile SET status=0 WHERE user_id=?", user);
        assertThat(deliveryService.createScheduledPage(scheduled, attempt, List.of(user), window)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isZero();
        jdbc.update("UPDATE user_profile SET status=1 WHERE user_id=?", user);
        assertThat(deliveryService.createScheduledPage(scheduled, attempt, List.of(user), window)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isEqualTo(1);
        jdbc.update("UPDATE campaign_execution SET status='DONE' WHERE id=?", execution);
        jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?", campaign);
    }

    @Test
    void staleDeliveryAcknowledgementsAndClaimsCannotOverwriteTheNewAttempt() {
        Fixture f = fixture(false);
        LocalDateTime oldClaim = LocalDateTime.now().withNano(0).minusMinutes(6);
        jdbc.update("UPDATE delivery_task SET status='PROCESSING',processing_at=? WHERE id=?", oldClaim, f.task);
        var staleTask = deliveryTasks.selectById(f.task);
        jdbc.update("UPDATE delivery_task SET status='PENDING',dispatch_status='PENDING',processing_at=NULL,retry_count=1 WHERE id=?", f.task);
        assertThat(deliveryTasks.markPublishedForAttempt(f.task, 0, "PENDING", LocalDateTime.now())).isZero();
        assertThat(deliveryTasks.markPublishedForAttempt(f.task, 1, "WAIT_RETRY", LocalDateTime.now())).isZero();
        assertThat(jdbc.queryForObject("SELECT dispatch_status FROM delivery_task WHERE id=?", String.class, f.task))
                .isEqualTo("PENDING");
        assertThat(deliveryTasks.tryClaimAt(f.task, oldClaim.plusMinutes(6))).isEqualTo(1);
        assertThat(deliveryRecovery.storeChannelReceipt(staleTask, oldClaim)).isNull();
        assertThat(count("SELECT COUNT(*) FROM in_app_message WHERE business_key=?", f.task)).isZero();
        assertThat(deliveryTasks.markPublishedForAttempt(f.task, 1, "PENDING", LocalDateTime.now())).isEqualTo(1);
        jdbc.update("UPDATE delivery_task SET status='CANCELLED' WHERE id=?", f.task);
    }

    @Test
    void duplicateReplayKeepsClaimAndBackoffAndStaleWorkerCannotRewriteTerminalState() {
        String eventId = "review-comp-lease-" + IDS.incrementAndGet();
        compensationTasks.ensureEventReplayPending(eventId, "{}");
        long id = count("SELECT id FROM data_compensation_task WHERE event_id=?", eventId);
        assertThat(compensationTasks.markProcessing(id)).isEqualTo(1);
        var stale = compensationTasks.selectById(id);
        compensationTasks.ensureEventReplayPending(eventId, "{}");
        assertThat(compensationTasks.selectById(id).getLockedAt()).isEqualTo(stale.getLockedAt());
        jdbc.update("UPDATE data_compensation_task SET status='PENDING',retry_count=1,locked_at=NULL,"
                + "next_retry_at=NOW()+INTERVAL 10 MINUTE WHERE id=?", id);
        LocalDateTime due = compensationTasks.selectById(id).getNextRetryAt();
        compensationTasks.ensureEventReplayPending(eventId, "{}");
        assertThat(compensationTasks.selectById(id).getNextRetryAt()).isEqualTo(due);
        assertThat(compensationTasks.markProcessing(id)).isEqualTo(1);
        var current = compensationTasks.selectById(id);
        assertThat(compensationTasks.finishAttempt(current, "DONE", 1, null, null)).isEqualTo(1);
        assertThat(compensationTasks.finishAttempt(stale, "PENDING", 1, "old failure", 60L)).isZero();
        compensationTasks.ensureEventReplayPending(eventId, "{}");
        assertThat(compensationTasks.selectById(id).getStatus()).isEqualTo("DONE");
        assertThat(compensationTasks.selectById(id).getRetryCount()).isEqualTo(1);
    }

    @Test
    void kafkaRetriesWhenCompensationInsertFailsAndReplayReadsCanonicalFact() {
        long user = 16_000_000 + IDS.incrementAndGet();
        LocalDateTime time = LocalDateTime.now();
        String eventId = "review-comp-write-" + user;
        String dailyKey = "user:daily:" + user + ":" + time.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        var views = redis.<String, String>getMap(dailyKey, StringCodec.INSTANCE);
        var wrongType = redis.getBucket("user:rt:" + user, StringCodec.INSTANCE);
        wrongType.set("wrong type");
        jdbc.execute("CREATE TRIGGER review_reject_comp_insert BEFORE INSERT ON data_compensation_task "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='compensation temporarily unavailable'");
        try {
            post(body(eventId, user, "CONTENT_VIEW", time, Map.of()), null);
            stored(eventId, "NONE");
            assertThat(views.get("views")).isNull();
            assertThat(count("SELECT COUNT(*) FROM data_compensation_task WHERE event_id=?", eventId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER review_reject_comp_insert");
        }
        try {
            // No second publish. The original offset must remain retryable after the failed outbox write.
            await().atMost(Duration.ofSeconds(25)).until(() ->
                    count("SELECT COUNT(*) FROM data_compensation_task WHERE event_id=?", eventId) == 1);
            assertThat(views.get("views")).isNull();
            jdbc.update("UPDATE data_compensation_task SET payload=JSON_OBJECT('eventType','CLICK') WHERE event_id=?", eventId);
        } finally {
            wrongType.delete();
        }
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            compensation.execute();
            assertThat(count("SELECT COUNT(*) FROM data_compensation_task WHERE event_id=? AND status='DONE'", eventId))
                    .isEqualTo(1);
        });
        assertThat(views.get("views")).isEqualTo("1");
        assertThat(count("SELECT SUM(event_count) FROM user_metric_hourly WHERE user_id=? AND event_type='CONTENT_VIEW'", user))
                .isEqualTo(1);
    }


    @Test
    void decisionFailureAfterRedisUpdateReplaysWithoutCountingAgain() {
        long campaign = IDS.addAndGet(100);
        long user = 17_000_000 + campaign;
        String eventId = "review-decision-" + user;
        LocalDateTime time = LocalDateTime.now();
        String dailyKey = "user:daily:" + user + ":" + time.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        var views = redis.<String, String>getMap(dailyKey, StringCodec.INSTANCE);
        jdbc.update("INSERT INTO campaign(id,name,trigger_type,event_types,channel,message_template,status) "
                + "VALUES (?,?,'EVENT','CONTENT_VIEW','IN_APP','测试优惠','ACTIVE')", campaign, "PF_REVIEW_DECISION_" + campaign);
        jdbc.execute("CREATE TRIGGER review_reject_delivery BEFORE INSERT ON delivery_task FOR EACH ROW "
                + "BEGIN IF NEW.campaign_id = " + campaign + " THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='decision temporarily unavailable'; END IF; END");
        try {
            post(body(eventId, user, "CONTENT_VIEW", time, Map.of()), null);
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    count("SELECT COUNT(*) FROM data_compensation_task WHERE event_id=? AND status='PENDING'", eventId) == 1);
            assertThat(views.get("views")).isEqualTo("1");
            assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?", campaign)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER review_reject_delivery");
        }
        try {
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                compensation.execute();
                assertThat(count("SELECT COUNT(*) FROM delivery_task WHERE campaign_id=? AND status='SENT'", campaign))
                        .isEqualTo(1);
            });
            assertThat(views.get("views")).isEqualTo("1");
        } finally {
            jdbc.update("UPDATE campaign SET status='PAUSED' WHERE id=?", campaign);
        }
    }

    @Test
    void committedOrderOutboxRecoversRedisFailureAndDeduplicatesStableOrders() {
        long user = 18_000_000 + IDS.incrementAndGet();
        LocalDateTime time = LocalDateTime.now();
        String first = user + "-first";
        var script = redis.getScript(StringCodec.INSTANCE);
        var keys = List.<Object>of("delay:attribution", "review:held:delay:attribution");
        script.eval(org.redisson.api.RScript.Mode.READ_WRITE, """
                if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('RENAME', KEYS[1], KEYS[2]) end
                redis.call('SET', KEYS[1], 'wrong type')
                return 1
                """, org.redisson.api.RScript.ReturnType.INTEGER, keys);
        try {
            fact(user, "first", "ORDER_PAID", time, Map.of("orderId", " stable "));
            fact(user, "duplicate", "ORDER_PAID", time.plusSeconds(1), Map.of("orderId", "stable"));
            assertThat(count("SELECT COUNT(*) FROM user_event WHERE user_id=? AND event_type='ORDER_PAID'", user))
                    .isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM attribution_task WHERE user_id=? AND status='PENDING'", user))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT target_event_id FROM attribution_task WHERE user_id=?", String.class, user))
                    .isEqualTo(first);
        } finally {
            script.eval(org.redisson.api.RScript.Mode.READ_WRITE, """
                    redis.call('DEL', KEYS[1])
                    if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('RENAME', KEYS[2], KEYS[1]) end
                    return 1
                    """, org.redisson.api.RScript.ReturnType.INTEGER, keys);
        }
        var queue = redis.<String>getScoredSortedSet("delay:attribution", StringCodec.INSTANCE);
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            attribution.reconcilePendingTasks();
            return queue.contains(first);
        });
    }

    private void fact(long user, String suffix, String type, LocalDateTime time, Map<String, Object> properties) {
        Map<String, Object> event = body(user + "-" + suffix, user, type, time, properties);
        event.put("receivedAt", time.toString());
        event.put("effectiveEventTime", time.toString());
        persistence.persist(event);
    }

}
