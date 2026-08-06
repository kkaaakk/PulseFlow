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

/**
 * 站内信发送记录。
 * business_key = delivery_task.id，UNIQUE KEY uk_business_key 保证渠道幂等：
 * 重试命中 UK 即视为已发送成功，不重复投递站内信。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("in_app_message")
public class InAppMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键 = delivery_task.id，幂等保障 */
    private Long businessKey;

    private Long userId;

    private Long campaignId;

    private String content;

    @Builder.Default
    private Integer readStatus = 0;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
