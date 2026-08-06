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
@TableName("attribution_record")
public class AttributionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clickEventId;

    private String targetEventId;

    private Long userId;

    private Long campaignId;

    private Long taskId;

    @Builder.Default
    private String attributionModel = "CLICK_LAST_TOUCH";

    @Builder.Default
    private Integer attributionWindowHours = 24;

    private LocalDateTime creditedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
