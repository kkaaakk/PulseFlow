package com.pulseflow.boot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Upgrade a populated V7 database; legacy facts/attribution IDs must survive. */
@EnabledIfEnvironmentVariable(named="PULSEFLOW_TEST_DOCKER",matches="true")
@Testcontainers
class MarketingClickMigrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pulseflow_click_upgrade").withUsername("test").withPassword("test");

    @Test void upgradePreservesLegacyIdsFailsClosedAndEnforcesSourceUniqueness() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())
                .locations("classpath:db/migration").target("7").load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        jdbc.execute("INSERT INTO user_event (event_id,user_id,event_type,event_time,received_at,effective_event_time,properties) "
                + "VALUES ('legacy-click',10,'CLICK',NOW(),NOW(),NOW(),'{\"taskId\":5}')");
        jdbc.execute("INSERT INTO click_event (id,user_id,task_id,click_time) VALUES (41,10,5,NOW()),(42,10,5,NOW())");
        jdbc.execute("INSERT INTO attribution_record (id,click_event_id,target_event_id,user_id,task_id,credited_at) "
                + "VALUES (51,41,'legacy-order',10,5,NOW())");
        Flyway flyway=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM click_event WHERE id IN (41,42) AND source_event_id IS NULL",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT click_event_id FROM attribution_record WHERE id=51",Long.class)).isEqualTo(41);
        assertThat(jdbc.queryForObject("SELECT marketing_trusted FROM user_event WHERE event_id='legacy-click'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT marketing_click_status FROM user_event WHERE event_id='legacy-click'",String.class)).isEqualTo("NONE");
        jdbc.execute("INSERT INTO click_event (source_event_id,user_id,task_id,click_time) VALUES ('new-source',10,5,NOW())");
        assertThatThrownBy(()->jdbc.execute("INSERT INTO click_event (source_event_id,user_id,task_id,click_time) VALUES ('new-source',10,5,NOW())"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }
}
