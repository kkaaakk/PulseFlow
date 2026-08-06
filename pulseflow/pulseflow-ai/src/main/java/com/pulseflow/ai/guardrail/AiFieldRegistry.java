package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.domain.campaign.Operator;
import com.pulseflow.ai.domain.campaign.ValueType;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for the fields AI may reference in a Campaign DSL.
 *
 * <p>The same in-memory list drives:</p>
 * <ul>
 *   <li>prompt construction ({@link com.pulseflow.ai.prompt.CampaignIntentPromptBuilder})</li>
 *   <li>DSL validation ({@link com.pulseflow.ai.guardrail.CampaignDslValidator})</li>
 *   <li>DSL→CampaignRule conversion ({@code DslToRuleConverter})</li>
 * </ul>
 *
 * <p>Per design §7.4, Java and Prompt never maintain separate field lists.</p>
 */
@Slf4j
@Component
public class AiFieldRegistry {

    private final Map<String, FieldDescriptor> fields = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        register(FieldDescriptor.builder()
                .fieldCode("todayViews")
                .displayName("今日浏览数")
                .description("用户当天的内容浏览次数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("1000"))
                .sourceType("REALTIME_PROFILE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("cartItemCount")
                .displayName("购物车商品数")
                .description("用户当前购物车中的商品数量")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("100"))
                .sourceType("REALTIME_PROFILE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("searchCount1h")
                .displayName("近1小时搜索次数")
                .description("用户最近1小时的搜索次数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("100"))
                .sourceType("WINDOW_PROFILE")
                .metricType("search_1h")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("activeDays7d")
                .displayName("近7天活跃天数")
                .description("最近7天内有内容浏览的天数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("7"))
                .sourceType("WINDOW_PROFILE")
                .metricType("active_7d")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("viewCount7d")
                .displayName("近7天浏览次数")
                .description("最近7天内容浏览总次数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("1000"))
                .sourceType("WINDOW_PROFILE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("spend30d")
                .displayName("近30天消费金额")
                .description("最近30天订单支付金额累计")
                .valueType(ValueType.DECIMAL)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("1000000"))
                .sourceType("WINDOW_PROFILE")
                .metricType("spend_30d")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("orderCount30d")
                .displayName("近30天订单数")
                .description("最近30天支付订单数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("1000"))
                .sourceType("WINDOW_PROFILE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("daysSinceLastPurchase")
                .displayName("距上次购买天数")
                .description("距离最近一次 ORDER_PAID 事件的天数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.EQ, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("365"))
                .sourceType("WINDOW_PROFILE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("registrationDays")
                .displayName("注册天数")
                .description("用户注册至今的天数")
                .valueType(ValueType.INTEGER)
                .allowedOperators(List.of(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE))
                .minimum(BigDecimal.ZERO)
                .maximum(new BigDecimal("3650"))
                .sourceType("USER_PROFILE")
                .enabled(true)
                .build());

        // Tag-based fields (BOOLEAN). DecisionEngine.hasTag is used at evaluation time.
        register(FieldDescriptor.builder()
                .fieldCode("HIGH_VALUE")
                .displayName("高价值用户")
                .description("是否高价值用户标签")
                .valueType(ValueType.BOOLEAN)
                .allowedOperators(List.of(Operator.EQ))
                .sourceType("TAG")
                .tagName("HIGH_VALUE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("PRICE_SENSITIVE")
                .displayName("价格敏感用户")
                .description("是否价格敏感用户标签")
                .valueType(ValueType.BOOLEAN)
                .allowedOperators(List.of(Operator.EQ))
                .sourceType("TAG")
                .tagName("PRICE_SENSITIVE")
                .enabled(true)
                .build());

        register(FieldDescriptor.builder()
                .fieldCode("CHURN_RISK")
                .displayName("流失风险用户")
                .description("是否有流失风险标签")
                .valueType(ValueType.BOOLEAN)
                .allowedOperators(List.of(Operator.EQ))
                .sourceType("TAG")
                .tagName("CHURN_RISK")
                .enabled(true)
                .build());

        log.info("AiFieldRegistry initialised with {} fields ({} enabled)",
                fields.size(), fields.values().stream().filter(FieldDescriptor::isEnabled).count());
    }

    private void register(FieldDescriptor descriptor) {
        fields.put(descriptor.getFieldCode(), descriptor);
    }

    public FieldDescriptor get(String fieldCode) {
        return fields.get(fieldCode);
    }

    public boolean contains(String fieldCode) {
        return fields.containsKey(fieldCode);
    }

    public boolean isEnabled(String fieldCode) {
        FieldDescriptor d = fields.get(fieldCode);
        return d != null && d.isEnabled();
    }

    public List<FieldDescriptor> enabledFields() {
        return fields.values().stream().filter(FieldDescriptor::isEnabled).collect(Collectors.toList());
    }

    public Set<String> enabledFieldCodes() {
        return fields.values().stream()
                .filter(FieldDescriptor::isEnabled)
                .map(FieldDescriptor::getFieldCode)
                .collect(Collectors.toSet());
    }

    /**
     * Compact human-readable description injected into the intent prompt.
     * Format per field: {@code fieldCode | displayName | valueType | operators | min-max | sourceType}.
     */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        for (FieldDescriptor f : enabledFields()) {
            sb.append("- ").append(f.getFieldCode())
                    .append(" (").append(f.getDisplayName()).append(")")
                    .append(" valueType=").append(f.getValueType())
                    .append(" operators=").append(f.getAllowedOperators())
                    .append(" min=").append(f.getMinimum())
                    .append(" max=").append(f.getMaximum());
            if (f.getEnumValues() != null && !f.getEnumValues().isEmpty()) {
                sb.append(" enum=").append(f.getEnumValues());
            }
            sb.append(" source=").append(f.getSourceType());
            if (f.getDescription() != null) {
                sb.append(" // ").append(f.getDescription());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FieldDescriptor {
        private String fieldCode;
        private String displayName;
        private String description;
        private ValueType valueType;
        private List<Operator> allowedOperators;
        private BigDecimal minimum;
        private BigDecimal maximum;
        private List<String> enumValues;
        private String sourceType;
        /** metric_type in user_behavior_summary, when sourceType=WINDOW_PROFILE. */
        private String metricType;
        /** tag_name in user_tag, when sourceType=TAG. */
        private String tagName;
        private boolean enabled;
    }
}
