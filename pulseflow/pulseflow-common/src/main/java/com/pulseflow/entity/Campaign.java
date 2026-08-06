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
@TableName("campaign")
public class Campaign {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String triggerType;

    private String cronExpression;

    private String eventTypes;

    private Integer delaySeconds;

    private String channel;

    private String messageTemplate;

    /** Operator who created this campaign (for resource ownership checks). */
    private Long createdBy;

    @Builder.Default
    private Integer userDailyLimit = 3;

    @Builder.Default
    private Integer campaignWeeklyLimit = 1;

    @Builder.Default
    private String status = "DRAFT";

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime nextTriggerAt;

    private LocalDateTime lastTriggerAt;

    @Builder.Default
    private Integer version = 0;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
