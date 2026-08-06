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

    /**
     * Atomic upsert for compensation task.
     * On duplicate (event_id, task_type) the row is explicitly restored to PENDING:
     * retry_count reset to 0, locked_at cleared, next_retry_at set to NOW(),
     * last_error refreshed. This matches the design's "INSERT ... ON DUPLICATE KEY
     * UPDATE status='PENDING', retry_count=0, next_retry_at=NOW(), locked_at=NULL"
     * and avoids the non-atomic insert-then-update sequence.
     */
    @Insert("INSERT INTO data_compensation_task (event_id, task_type, payload, status, retry_count, max_retry, next_retry_at, locked_at, last_error) " +
            "VALUES (#{eventId}, #{taskType}, #{payload}, 'PENDING', 0, 5, NOW(), NULL, #{lastError}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  status = 'PENDING', " +
            "  retry_count = 0, " +
            "  max_retry = 5, " +
            "  next_retry_at = NOW(), " +
            "  locked_at = NULL, " +
            "  last_error = VALUES(last_error)")
    int upsertPendingRestore(@Param("eventId") String eventId,
                             @Param("taskType") String taskType,
                             @Param("payload") String payload,
                             @Param("lastError") String lastError);

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
            "WHERE id = #{id}")
    int markProcessing(@Param("id") Long id);
}
