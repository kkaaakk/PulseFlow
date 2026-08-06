package com.pulseflow.job.handler;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataCleanupJob {

    private final JdbcTemplate jdbcTemplate;

    @XxlJob("dataCleanupJob")
    public void execute() {
        log.info("DataCleanupJob started");

        LocalDateTime expiry30Days = LocalDateTime.now().minusDays(30);
        LocalDateTime expiry90Days = LocalDateTime.now().minusDays(90);

        try {
            int compDeleted = jdbcTemplate.update(
                    "DELETE FROM data_compensation_task WHERE status IN ('DONE','FAILED') AND updated_at < ?",
                    java.sql.Timestamp.valueOf(expiry30Days));
            log.info("Cleaned {} expired compensation tasks", compDeleted);

            int attrDeleted = jdbcTemplate.update(
                    "DELETE FROM attribution_task WHERE created_at < ?",
                    java.sql.Timestamp.valueOf(expiry90Days));
            log.info("Cleaned {} expired attribution tasks", attrDeleted);

            int clickDeleted = jdbcTemplate.update(
                    "DELETE FROM click_event WHERE created_at < ?",
                    java.sql.Timestamp.valueOf(expiry90Days));
            log.info("Cleaned {} expired click events", clickDeleted);

            int deliveryDeleted = jdbcTemplate.update(
                    "DELETE FROM delivery_record WHERE created_at < ?",
                    java.sql.Timestamp.valueOf(expiry90Days));
            log.info("Cleaned {} expired delivery records", deliveryDeleted);

            // user_tag 保留历史（uk = user_id + tag_name + calculated_at），
            // 清理 90 天前的旧标签记录，只保留近期历史供 hasTag 查最新值。
            int tagDeleted = jdbcTemplate.update(
                    "DELETE FROM user_tag WHERE calculated_at < ?",
                    java.sql.Timestamp.valueOf(expiry90Days));
            log.info("Cleaned {} expired user tag records", tagDeleted);

        } catch (Exception e) {
            log.error("DataCleanupJob failed", e);
            throw e;
        }

        log.info("DataCleanupJob completed");
    }
}
