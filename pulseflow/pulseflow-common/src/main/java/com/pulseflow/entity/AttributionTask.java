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
@TableName("attribution_task")
public class AttributionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String targetEventId;

    private Long userId;

    private String targetEventType;

    private LocalDateTime targetEventTime;

    @Builder.Default
    private String status = "PENDING";

    private LocalDateTime graceUntil;

    private Long matchedTaskId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
