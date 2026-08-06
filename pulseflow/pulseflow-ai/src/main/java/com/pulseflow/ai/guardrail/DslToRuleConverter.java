package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.domain.campaign.AudienceCondition;
import com.pulseflow.ai.domain.campaign.AudienceGroup;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.Operator;
import com.pulseflow.ai.domain.campaign.ValueType;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a validated {@link CampaignDsl} into the existing
 * {@code campaign_rule} JSON shape that {@link com.pulseflow.campaign.decision.DecisionEngine}
 * understands.
 *
 * <p>DecisionEngine's PROFILE rule expects rule_config:</p>
 * <pre>
 * TAG:     {"tagName":"HIGH_VALUE","operator":"EQ","value":"1"}
 * METRIC:  {"metricType":"spend_30d","operator":"GTE","threshold":500}
 * </pre>
 *
 * <p>Boolean tag fields → TAG rule. Numeric fields → METRIC rule.
 * String fields → unsupported in v1 (rejected at validation).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DslToRuleConverter {

    private final AiFieldRegistry fieldRegistry;

    /**
     * Returns one rule_config JSON per DSL condition. AND/OR logic is implicit
     * (DecisionEngine ANDs all rules of a campaign; OR is approximated by
     * emitting a single composite rule in future — v1 supports AND only via
     * separate rows).
     */
    public List<ConvertedRule> convert(CampaignDsl dsl) {
        List<ConvertedRule> out = new ArrayList<>();
        AudienceGroup audience = dsl.getAudience();
        if (audience == null || audience.getConditions() == null) return out;

        for (AudienceCondition c : audience.getConditions()) {
            AiFieldRegistry.FieldDescriptor fd = fieldRegistry.get(c.getField());
            if (fd == null) {
                log.warn("DSL→Rule: skipping unknown field {}", c.getField());
                continue;
            }
            Map<String, Object> ruleConfig = new LinkedHashMap<>();
            String ruleName;

            if ("TAG".equals(fd.getSourceType())) {
                ruleConfig.put("tagName", fd.getTagName());
                ruleConfig.put("operator", c.getOperator());
                boolean b = toBoolean(c.getValue());
                ruleConfig.put("value", b ? "1" : "0");
                ruleName = "TAG:" + fd.getTagName();
            } else if ("WINDOW_PROFILE".equals(fd.getSourceType()) || "REALTIME_PROFILE".equals(fd.getSourceType())) {
                ruleConfig.put("metricType", fd.getMetricType() != null ? fd.getMetricType() : c.getField());
                ruleConfig.put("operator", c.getOperator());
                ruleConfig.put("threshold", toThreshold(c.getValue(), fd.getValueType()));
                ruleName = "METRIC:" + ruleConfig.get("metricType");
            } else if ("USER_PROFILE".equals(fd.getSourceType())) {
                // registrationDays etc. — v1 approximates as metric on user_behavior_summary
                // if metricType exists, else skip (warned in validation).
                ruleConfig.put("metricType", fd.getMetricType() != null ? fd.getMetricType() : c.getField());
                ruleConfig.put("operator", c.getOperator());
                ruleConfig.put("threshold", toThreshold(c.getValue(), fd.getValueType()));
                ruleName = "PROFILE:" + c.getField();
            } else {
                log.warn("DSL→Rule: unsupported sourceType {} for field {}", fd.getSourceType(), c.getField());
                continue;
            }
            out.add(new ConvertedRule(ruleName, "PROFILE", JsonUtil.toJson(ruleConfig)));
        }
        return out;
    }

    private boolean toBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private long toThreshold(Object v, ValueType type) {
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * One converted rule row to insert into campaign_rule.
     */
    public record ConvertedRule(String ruleName, String ruleType, String ruleConfigJson) {}
}
