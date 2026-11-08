package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.DataCompensationTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface DataCompensationTaskMapper extends BaseMapper<DataCompensationTask> {
    /** Never reset an existing retry budget or claim on HTTP/Kafka duplication. */
    @Insert("INSERT INTO data_compensation_task "
            + "(event_id,task_type,payload,status,retry_count,max_retry,next_retry_at,last_error) "
            + "VALUES (#{eventId},'MARKETING_CLICK',JSON_OBJECT('eventId',#{eventId}),"
            + "'PENDING',0,5,NOW(),'DELIVERY_NOT_VISIBLE') "
            + "ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)")
    int ensureMarketingClickPending(@Param("eventId") String eventId);

    /** Duplicate deliveries preserve the current lease, backoff and terminal result. */
    @Insert("""
            INSERT INTO data_compensation_task
                (event_id, task_type, payload, status, retry_count, max_retry, next_retry_at, last_error)
            VALUES (#{eventId}, 'EVENT_REPLAY', #{payload}, 'PENDING', 0, 5, NOW(), 'phase2_or_phase3_failed')
            ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)
            """)
    int ensureEventReplayPending(@Param("eventId") String eventId, @Param("payload") String payload);

    /**
     * 领取一个到期的 PENDING 补偿任务（FOR UPDATE SKIP LOCKED）。
     *
     * <p>关键约束：必须过滤 {@code retry_count < max_retry}。MyBatis-Plus 的
     * LambdaQueryWrapper 不支持列间比较，只能用自定义 SQL。不过滤的话，达到重试
     * 上限但仍 PENDING 的任务会被反复领取、反复失败，浪费资源且永远不会被
     * CompensationJob 的 stuck-recovery 标为 FAILED。</p>
     */
    @Select("SELECT * FROM data_compensation_task " +
            "WHERE status = 'PENDING' " +
            "AND next_retry_at <= NOW() " +
            "AND retry_count < max_retry " +
            "ORDER BY id ASC " +
            "LIMIT 1 FOR UPDATE SKIP LOCKED")
    DataCompensationTask selectOneDueUnderRetryForUpdate();

    /**
     * 标记领取（status → PROCESSING，locked_at = NOW）。
     */
    @Update("UPDATE data_compensation_task SET status = 'PROCESSING', locked_at = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int markProcessing(@Param("id") Long id);

    @Update("""
            UPDATE data_compensation_task
            SET status = #{status}, retry_count = #{retryCount}, last_error = #{error}, locked_at = NULL,
                next_retry_at = TIMESTAMPADD(SECOND, #{delaySeconds}, NOW())
            WHERE id = #{claim.id} AND status = 'PROCESSING' AND retry_count = #{claim.retryCount}
              AND locked_at = #{claim.lockedAt}
            """)
    int finishAttempt(@Param("claim") DataCompensationTask claim, @Param("status") String status,
                      @Param("retryCount") int retryCount, @Param("error") String error,
                      @Param("delaySeconds") Long delaySeconds);
}
