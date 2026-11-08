package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.DeliveryTask;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Mapper
@Repository
public interface DeliveryTaskMapper extends BaseMapper<DeliveryTask> {
    @Insert("""
            INSERT INTO delivery_task
                (campaign_id, user_id, dedup_key, trigger_event_id, channel, message_content, created_at)
            SELECT #{task.campaignId}, #{task.userId}, #{task.dedupKey}, #{task.triggerEventId},
                   #{task.channel}, #{task.messageContent}, #{task.createdAt}
            FROM user_event added
            WHERE added.event_id = #{task.triggerEventId} AND added.user_id = #{task.userId}
              AND added.event_type = 'ADD_CART' AND added.cart_item_id = #{cartItemId}
              AND added.id = (
                  SELECT id FROM user_event
                  WHERE user_id = #{task.userId} AND cart_item_id = #{cartItemId}
            """ + UserEventMapper.CART_MUTATION_ORDER + ")")
    @Options(useGeneratedKeys = true, keyProperty = "task.id")
    int insertIfLatestCartAddition(@Param("task") DeliveryTask task, @Param("cartItemId") String cartItemId);

    /** An acknowledgement from a previous retry or from WAIT_RETRY cannot hide a newly pending wake-up. */
    @Update("""
            UPDATE delivery_task SET dispatch_status = 'PUBLISHED', published_at = #{publishedAt}
            WHERE id = #{id} AND dispatch_status = 'PENDING' AND retry_count = #{retryCount}
              AND (status <> 'PENDING' OR #{publishedStatus} = 'PENDING')
            """)
    int markPublishedForAttempt(@Param("id") Long id, @Param("retryCount") Integer retryCount,
                                @Param("publishedStatus") String publishedStatus,
                                @Param("publishedAt") LocalDateTime publishedAt);

    @Update("UPDATE delivery_task SET status='PROCESSING', processing_at=#{now} "
            + "WHERE id=#{id} AND status='PENDING'")
    int tryClaimAt(@Param("id") Long id, @Param("now") LocalDateTime now);

    default int tryClaim(Long id) {
        return tryClaimAt(id, LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Update("UPDATE delivery_task SET first_send_attempt_at=COALESCE(first_send_attempt_at,#{attemptAt}) "
            + "WHERE id=#{id} AND status='PROCESSING' AND processing_at=#{claim}")
    int markAttempt(@Param("id") Long id, @Param("claim") LocalDateTime claim, @Param("attemptAt") LocalDateTime attemptAt);

    @Select("""
            SELECT id FROM delivery_task WHERE id = #{id} AND status = 'PROCESSING'
              AND processing_at = #{claim} FOR UPDATE
            """)
    Long lockChannelClaim(@Param("id") Long id, @Param("claim") LocalDateTime claim);

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
