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

    /**
     * 按各表保留期限清理历史事件、指标与任务等数据；执行失败记录日志并向调度器抛出。
     */
    @XxlJob("dataCleanupJob")
    public void execute() {
        log.info("DataCleanupJob started");

        LocalDateTime expiry30Days = LocalDateTime.now().minusDays(30);
        LocalDateTime expiry90Days = LocalDateTime.now().minusDays(90);

        try {
            int compDeleted = jdbcTemplate.update(
                    "DELETE FROM data_compensation_task WHERE status IN ('DONE','FAILED') AND updated_at < ? "
                            + "AND (task_type <> 'MARKETING_CLICK' OR NOT EXISTS (SELECT 1 FROM user_event e "
                            + "WHERE e.event_id = data_compensation_task.event_id AND e.marketing_click_status = 'PENDING'))",
                    java.sql.Timestamp.valueOf(expiry30Days));
            log.info("Cleaned {} expired compensation tasks", compDeleted);

            int attrDeleted = jdbcTemplate.update(
                    "DELETE FROM attribution_task WHERE created_at < ? "
                            + "AND paid_order_id IS NULL AND status IN ('MATCHED','EXPIRED')",
                    java.sql.Timestamp.valueOf(expiry90Days));
            log.info("Cleaned {} expired attribution tasks", attrDeleted);

            int clickDeleted = jdbcTemplate.update(
                    "DELETE FROM click_event WHERE created_at < ?",
                    java.sql.Timestamp.valueOf(expiry90Days));
            log.info("Cleaned {} expired click events", clickDeleted);

            // delivery_task now owns the durable send result. Keep task/channel idempotency
            // facts together; deleting just a receipt would make old deliveries resendable.

            // Retired tag strategies have no writer. Keep existing tag facts for legacy rules.

        } catch (Exception e) {
            log.error("DataCleanupJob failed", e);
            throw e;
        }

        log.info("DataCleanupJob completed");
    }
}
