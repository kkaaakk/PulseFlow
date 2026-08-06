package com.pulseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_behavior_summary")
public class UserBehaviorSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String metricType;

    @Builder.Default
    private BigDecimal metricValue = new BigDecimal("0.00");

    private LocalDateTime calculatedAt;

    private LocalDateTime windowStart;

    private LocalDateTime windowEnd;
}
