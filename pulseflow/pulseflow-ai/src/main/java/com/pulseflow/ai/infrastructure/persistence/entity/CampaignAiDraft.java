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
 * AI-generated Campaign draft. Created by /parse, updated by /drafts/{id},
 * confirmed by /campaigns/from-ai-draft/{id}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("campaign_ai_draft")
public class CampaignAiDraft {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long operatorId;

    private String sourceText;

    private Integer schemaVersion;

    /** DSL JSON. */
    private String dslJson;

    /** DraftStatus name. */
    private String validationStatus;

    /** List of validation errors JSON. */
    private String validationErrorsJson;

    private String warningsJson;

    private Long estimatedAudienceCount;

    private String profileDataVersion;

    private Long confirmedCampaignId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime confirmedAt;
}
