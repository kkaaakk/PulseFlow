package com.pulseflow.ai.infrastructure.persistence.entity;

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

/**
 * Audit row for every AI call. UK on {@code request_id} guarantees one record
 * per logical request even under retry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_generation_record")
public class AiGenerationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long operatorId;

    /** AiTaskType name. */
    private String taskType;

    private String provider;

    private String model;

    private String promptVersion;

    /** Sanitised input JSON (no PII). */
    private String sanitizedInputJson;

    /** Model raw output JSON. */
    private String structuredOutputJson;

    /** SUCCESS / FAILED / INVALID. */
    private String status;

    private String errorCode;

    private String errorMessage;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;

    /** Optional context for cross-correlation. */
    private Long draftId;

    private Long campaignId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
