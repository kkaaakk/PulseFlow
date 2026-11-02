package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.DeliveryTask;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Mapper
@Repository
public interface DeliveryTaskMapper extends BaseMapper<DeliveryTask> {
    @Update("UPDATE delivery_task SET status='PROCESSING', processing_at=#{now} "
            + "WHERE id=#{id} AND status='PENDING'")
    int tryClaimAt(@Param("id") Long id, @Param("now") LocalDateTime now);

    default int tryClaim(Long id) {
        return tryClaimAt(id, LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Update("UPDATE delivery_task SET first_send_attempt_at=COALESCE(first_send_attempt_at,#{attemptAt}) "
            + "WHERE id=#{id} AND status='PROCESSING' AND processing_at=#{claim}")
    int markAttempt(@Param("id") Long id, @Param("claim") LocalDateTime claim, @Param("attemptAt") LocalDateTime attemptAt);

    // EMAIL cannot deduplicate an external effect: reserve exactly one automatic attempt.
    @Update("UPDATE delivery_task SET first_send_attempt_at=#{attemptAt} WHERE id=#{id} "
            + "AND status='PROCESSING' AND processing_at=#{claim} AND first_send_attempt_at IS NULL")
    int reserveEmailAttempt(@Param("id") Long id, @Param("claim") LocalDateTime claim, @Param("attemptAt") LocalDateTime attemptAt);

    @Update("UPDATE delivery_task SET status='SENT', sent_at=#{sentAt}, "
            + "first_send_attempt_at=COALESCE(first_send_attempt_at,#{sentAt}), "
            + "next_retry_at=NULL, last_error=NULL WHERE id=#{id} AND sent_at IS NULL "
            + "AND status IN ('PENDING','PROCESSING','WAIT_RETRY','FAILED')")
    int markSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    @Update("UPDATE delivery_task SET status=#{status}, next_retry_at=#{nextRetryAt}, "
            + "last_error=#{error}, retry_count=retry_count+#{increment} "
            + "WHERE id=#{id} AND status='PROCESSING' AND processing_at=#{claim} AND sent_at IS NULL")
    int finishAttempt(@Param("id") Long id, @Param("claim") LocalDateTime claim,
                      @Param("status") String status, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("error") String error, @Param("increment") int increment);
}
