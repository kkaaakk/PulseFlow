package com.pulseflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("data_compensation_task")
public class DataCompensationTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String taskType;

    private String payload;

    @Builder.Default
    private String status = "PENDING";

    @Builder.Default
    private Integer retryCount = 0;

    @Builder.Default
    private Integer maxRetry = 5;

    private LocalDateTime nextRetryAt;

    private LocalDateTime lockedAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
