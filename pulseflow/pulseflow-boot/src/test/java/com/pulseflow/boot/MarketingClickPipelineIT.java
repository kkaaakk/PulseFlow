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
import com.pulseflow.job.handler.CompensationJob;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import java.time.*;
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
        post(body(id, f.user, "LOGIN", LocalDateTime.now(), Map.of()), null);
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

}
