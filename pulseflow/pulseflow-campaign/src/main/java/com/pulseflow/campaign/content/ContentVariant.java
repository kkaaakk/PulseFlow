package com.pulseflow.campaign.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个文案变体，保存类型、标题、正文及校验信息。
 *
 * A single proposed content variant.
 *
 * <p>Allowed {@code type} values: DIRECT_BENEFIT / URGENCY / PERSONALIZED.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentVariant {

    private String type;
    private String title;
    private String body;
    private String strategy;
}
