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
@TableName("campaign_execution")
public class CampaignExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private LocalDateTime scheduledAt;

    @Builder.Default
    private String status = "PENDING";

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Builder.Default
    private Integer retryCount = 0;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
