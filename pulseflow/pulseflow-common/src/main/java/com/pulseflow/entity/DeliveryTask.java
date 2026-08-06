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
@TableName("delivery_task")
public class DeliveryTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private Long userId;

    private String dedupKey;

    private String triggerEventId;

    private String channel;

    @Builder.Default
    private String status = "PENDING";

    @Builder.Default
    private String dispatchStatus = "PENDING";

    private String messageContent;

    @Builder.Default
    private Integer retryCount = 0;

    private LocalDateTime nextRetryAt;

    private LocalDateTime processingAt;

    private LocalDateTime publishedAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
