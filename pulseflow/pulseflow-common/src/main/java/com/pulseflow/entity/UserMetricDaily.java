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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_metric_daily")
public class UserMetricDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate metricDate;

    private String eventType;

    @Builder.Default
    private Integer eventCount = 0;

    @Builder.Default
    private Long durationSum = 0L;

    @Builder.Default
    private BigDecimal amountSum = new BigDecimal("0.00");

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
