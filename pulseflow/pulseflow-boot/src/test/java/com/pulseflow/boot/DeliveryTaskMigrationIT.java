package com.pulseflow.boot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PULSEFLOW_TEST_DOCKER",matches="true")
@Testcontainers
class DeliveryTaskMigrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pulseflow_delivery_upgrade").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1");
    JdbcTemplate jdbc;
    Flyway flyway(String target) {
        var config=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if(target!=null) config.target(target);
        return config.load();
    }
    @BeforeEach void oldSchema() {
        flyway("8").clean(); flyway("8").migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
    }
    void task(long id,String status,String channel) {
        jdbc.update("INSERT INTO delivery_task(id,campaign_id,user_id,dedup_key,channel,status) VALUES (?,9,10,?,?,?)",
                id,"upgrade-"+id,channel,status);
    }
    void record(long id,String status,String time) {
        jdbc.update("INSERT INTO delivery_record(task_id,campaign_id,user_id,channel,status,sent_at,error_msg) "
                +"VALUES (?,9,10,'IN_APP',?,?,'old failure')",id,status,time);
    }
    long n(String sql) { return jdbc.queryForObject(sql,Long.class); }

    @Test void upgradePreservesLegacyCohortsFailuresClicksAndAttributionAndRecoversOriginalTime() {
        task(1,"SENT","IN_APP"); record(1,"SENT","2026-10-02 10:01:00");
        jdbc.update("INSERT INTO in_app_message(business_key,user_id,campaign_id,created_at) VALUES(1,10,9,'2026-10-02 10:00:00')");
        task(2,"WAIT_RETRY","IN_APP"); record(2,"SENT","2026-10-02 11:00:00");
        task(3,"WAIT_RETRY","IN_APP"); record(3,"FAILED","2026-10-03 12:00:00");
        task(4,"CANCELLED","IN_APP"); task(5,"PENDING","EMAIL");
        // Channel committed before the process crashed, so no old send fact exists.
        task(6,"PROCESSING","PUSH");
        jdbc.update("INSERT INTO push_record(business_key,user_id,campaign_id,pushed_at) VALUES(6,10,9,'2026-10-03 13:00:00')");
        jdbc.execute("INSERT INTO user_event(event_id,user_id,event_type,event_time,received_at,effective_event_time,"
                +"marketing_trusted,marketing_click_status) VALUES('trusted-click',10,'CLICK',NOW(),NOW(),NOW(),1,'VALID')");
        jdbc.execute("INSERT INTO click_event(id,user_id,task_id,source_event_id,click_time) VALUES(41,10,1,'trusted-click',NOW()),(42,10,1,NULL,NOW())");
        jdbc.execute("INSERT INTO attribution_record(id,click_event_id,target_event_id,user_id,campaign_id,task_id,credited_at) "
                +"VALUES(51,41,'old-order',10,9,1,NOW())");
        assertThat(flyway("9").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='delivery_record'")).isZero();
        assertThat(n("SELECT COUNT(*) FROM delivery_task WHERE first_send_attempt_at IS NOT NULL")).isEqualTo(4);
        assertThat(n("SELECT COUNT(*) FROM delivery_task WHERE status='SENT' AND sent_at IS NOT NULL")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sent_at FROM delivery_task WHERE id=1",LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026,10,2,10,0));
        assertThat(jdbc.queryForObject("SELECT first_send_attempt_at FROM delivery_task WHERE id=1",LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026,10,2,10,1));
        assertThat(n("SELECT COUNT(*) FROM delivery_task WHERE status='WAIT_RETRY' AND last_error='old failure' AND sent_at IS NULL")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM click_event WHERE id IN(41,42)")).isEqualTo(2);
        assertThat(n("SELECT click_event_id FROM attribution_record WHERE id=51")).isEqualTo(41);
        assertThat(n("SELECT COUNT(*) FROM click_event WHERE source_event_id IS NULL")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM delivery_task WHERE first_send_attempt_at>='2026-10-02' AND first_send_attempt_at<'2026-10-03'")).isEqualTo(2);
        assertThat(flyway("9").migrate().migrationsExecuted).isZero();
        flyway("9").validate();
    }

    @ParameterizedTest
    @ValueSource(strings={"orphan","ownership","missing-time","failed-success","cancelled-success",
            "channel-owner","receipt-after-success","unverifiable-sent","ambiguous-email"})
    void unsafeHistoryBlocksBeforeDestructiveDdlAndRetainsAllOldData(String scenario) {
        task(1,"WAIT_RETRY","IN_APP"); record(1,"SENT","2026-10-02 10:00:00");
        switch(scenario) {
            case "orphan" -> jdbc.execute("DELETE FROM delivery_task WHERE id=1");
            case "ownership" -> jdbc.execute("UPDATE delivery_record SET user_id=99");
            case "missing-time" -> jdbc.execute("UPDATE delivery_record SET sent_at=NULL");
            case "failed-success" -> {
                jdbc.execute("UPDATE delivery_task SET status='SENT'");
                jdbc.execute("UPDATE delivery_record SET status='FAILED'");
            }
            case "cancelled-success" -> jdbc.execute("UPDATE delivery_task SET status='CANCELLED'");
            case "channel-owner" -> jdbc.execute("INSERT INTO in_app_message(business_key,user_id,campaign_id) VALUES(1,99,9)");
            case "receipt-after-success" -> jdbc.execute("INSERT INTO in_app_message(business_key,user_id,campaign_id,created_at) VALUES(1,10,9,'2026-10-02 11:00:00')");
            case "unverifiable-sent" -> { jdbc.execute("DELETE FROM delivery_record"); jdbc.execute("UPDATE delivery_task SET status='SENT'"); }
            case "ambiguous-email" -> { jdbc.execute("UPDATE delivery_task SET channel='EMAIL'"); jdbc.execute("UPDATE delivery_record SET channel='EMAIL'"); }
        }
        assertThatThrownBy(()->flyway("9").migrate()).hasStackTraceContaining("V9 blocked:");
        assertThat(n("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='delivery_record'")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='delivery_task' AND column_name='sent_at'")).isZero();
        assertThatThrownBy(()->flyway("9").validate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    }
    @Test void backfillFailureKeepsOldTableAndCanResumeAfterAuditedRepair() {
        task(1,"PENDING","IN_APP"); record(1,"SENT","2026-10-02 10:00:00");
        jdbc.execute("CREATE TRIGGER migration_write_failure BEFORE UPDATE ON delivery_task "
                +"FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='backfill_failure'");
        try {
            assertThatThrownBy(()->flyway("9").migrate()).hasStackTraceContaining("backfill_failure");
        } finally { jdbc.execute("DROP TRIGGER migration_write_failure"); }
        assertThat(n("SELECT COUNT(*) FROM delivery_record")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM delivery_task WHERE status='PENDING' AND sent_at IS NULL AND first_send_attempt_at IS NULL")).isEqualTo(1);
        assertThatThrownBy(()->flyway("9").validate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        // Test-only: the cause was inspected and removed; never automatically repair production.
        flyway("9").repair();
        assertThat(flyway("9").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM delivery_task WHERE status='SENT' AND sent_at IS NOT NULL")).isEqualTo(1);
        flyway("9").validate();
    }


    @Test
    void v10IndexesHistoricalPaymentAndCartFactsWithoutRewritingLegacyMetrics() {
        flyway("9").migrate();
        jdbc.execute("""
                INSERT INTO user_event(event_id,user_id,event_type,event_time,received_at,effective_event_time,properties)
                VALUES ('order-a',10,'ORDER_PAID',NOW(),NOW(),NOW(),'{"orderId":" ORD-A ","cartItemId":"sku-a"}'),
                       ('order-b',10,'ORDER_PAID',NOW(),NOW(),NOW(),'{"orderId":"ORD-A","cartItemId":"sku-a"}'),
                       ('order-legacy',10,'ORDER_PAID',NOW(),NOW(),NOW(),'{}'),
                       ('view-old',10,'CONTENT_VIEW',NOW(),NOW(),NOW(),'{"orderId":"ORD-A"}')
                """);
        jdbc.execute("INSERT INTO user_behavior_summary(user_id,metric_type,metric_value,calculated_at) "
                + "VALUES (10,'active_7d',99,NOW())");
        assertThat(flyway("10").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM user_event")).isEqualTo(4);
        assertThat(n("SELECT COUNT(*) FROM user_event WHERE paid_order_id='ORD-A' AND cart_item_id='sku-a'")).isEqualTo(2);
        assertThat(n("SELECT COUNT(*) FROM user_event WHERE paid_order_id IS NULL")).isEqualTo(2);
        assertThat(n("SELECT COUNT(*) FROM user_behavior_summary WHERE metric_type='active_7d' AND metric_value=99")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM user_behavior_summary WHERE metric_type='active_days_7d'")).isZero();
        jdbc.execute("""
                INSERT INTO attribution_task(target_event_id,user_id,target_event_type,target_event_time,grace_until)
                VALUES ('order-a',10,'ORDER_PAID',NOW(),NOW()), ('order-b',10,'ORDER_PAID',NOW(),NOW())
                """);
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM attribution_task WHERE paid_order_id IS NULL")).isEqualTo(2);
        assertThat(n("SELECT COUNT(*) FROM user_event WHERE cart_cancelled=1 AND cart_item_id='sku-a'")).isEqualTo(2);
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        flyway(null).validate();
    }

}
