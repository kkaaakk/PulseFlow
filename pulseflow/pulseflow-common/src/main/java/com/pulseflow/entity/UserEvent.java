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
@TableName("user_event")
public class UserEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private Long userId;

    private String eventType;

    private Long targetId;

    private LocalDateTime eventTime;

    private LocalDateTime receivedAt;

    private LocalDateTime effectiveEventTime;

    @Builder.Default
    private Integer clockSkew = 0;

    /**
     * JSON string
     */
    private String properties;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
