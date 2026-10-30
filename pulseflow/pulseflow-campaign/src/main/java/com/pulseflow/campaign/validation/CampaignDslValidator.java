package com.pulseflow.campaign.validation;

import com.pulseflow.campaign.dsl.AudienceCondition;
import com.pulseflow.campaign.dsl.AudienceGroup;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.dsl.CampaignSchedule;
import com.pulseflow.campaign.dsl.DslValidationResult;
import com.pulseflow.campaign.dsl.FrequencyCap;
import com.pulseflow.campaign.dsl.Operator;
import com.pulseflow.campaign.dsl.ValueType;
import com.pulseflow.common.enums.ChannelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Full validator for a proposed {@link CampaignDsl}.
 *
 * <p>Runs all checks per design §7.5:</p>
 * <pre>
 * 1. required fields
 * 2. unknown fields (handled by Jackson strict mode at parse time — best-effort here)
 * 3. field type / operator / value bounds (per CampaignFieldRegistry)
 * 4. enum validation (channel / objective / schedule.type / audience.logic)
 * 5. time validation (must be future, must have offset, timezone valid)
 * 6. frequency cap (maxTimes>0, windowHours>0)
 * 7. rule complexity (≤10 conditions, ≤2 nesting levels — v1 only 1 level)
 * 8. promotion facts required for VALIDATED, optional for NEEDS_CONFIRMATION
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignDslValidator {

    private static final int MAX_CONDITIONS = 10;
    private static final int MAX_NESTING_DEPTH = 2;
    private static final Set<String> VALID_LOGIC = Set.of("AND", "OR");
    private static final Set<String> VALID_SCHEDULE_TYPES = Set.of("ONCE");
    private static final Set<String> VALID_OBJECTIVES =
            Set.of("CONVERSION", "RETENTION", "ACTIVATION", "BRANDING");

    private final CampaignFieldRegistry fieldRegistry;

    /**
     * Validate the given DSL. Returns a result with:
     * <ul>
     *   <li>{@code valid=true} → status=VALIDATED</li>
     *   <li>{@code needsConfirmation=true} → status=NEEDS_CONFIRMATION</li>
     *   <li>{@code errors non-empty} → status=INVALID</li>
     * </ul>
     */
    public DslValidationResult validate(CampaignDsl dsl) {
        List<String> errors = new ArrayList<>();
        List<String> missingFields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (dsl == null) {
            return DslValidationResult.invalid(List.of("DSL is null"));
        }

        // 1. schemaVersion
        if (dsl.getSchemaVersion() == null || dsl.getSchemaVersion() != 1) {
            errors.add("schemaVersion must be 1");
        }

        // 2. campaignName
        if (dsl.getCampaignName() == null || dsl.getCampaignName().isBlank()) {
            errors.add("campaignName is required");
        } else if (dsl.getCampaignName().length() > 128) {
            errors.add("campaignName exceeds 128 chars");
        }

        // 3. objective
        if (dsl.getObjective() == null || !VALID_OBJECTIVES.contains(dsl.getObjective())) {
            errors.add("objective must be one of " + VALID_OBJECTIVES);
        }

        // 4. audience
        if (dsl.getAudience() == null) {
            errors.add("audience is required");
        } else {
            validateAudience(dsl.getAudience(), errors, warnings);
        }

        // 5. channel
        if (dsl.getChannel() == null || !isValidChannel(dsl.getChannel())) {
            errors.add("channel must be one of IN_APP/PUSH/EMAIL");
        }

        // 6. schedule
        if (dsl.getSchedule() == null) {
            errors.add("schedule is required");
        } else {
            validateSchedule(dsl.getSchedule(), errors, missingFields, warnings);
        }

        // 7. frequencyCap
        if (dsl.getFrequencyCap() == null) {
            errors.add("frequencyCap is required");
        } else {
            validateFrequencyCap(dsl.getFrequencyCap(), errors);
        }

        // 8. promotionFacts — required for VALIDATED, optional for NEEDS_CONFIRMATION
        boolean hasPromotions = dsl.getPromotionFacts() != null && !dsl.getPromotionFacts().isEmpty();
        if (!hasPromotions) {
            missingFields.add("promotionFacts");
        }

        // Final decision
        if (!errors.isEmpty()) {
            return DslValidationResult.invalid(errors);
        }
        if (!missingFields.isEmpty()) {
            return DslValidationResult.needsConfirmation(missingFields, warnings);
        }
        return DslValidationResult.ok(warnings);
    }

    /**
     * 检查人群组合逻辑、条件列表与各项条件，把错误和警告累积到调用方提供的列表。
     */
    private void validateAudience(AudienceGroup audience, List<String> errors, List<String> warnings) {
        if (audience.getLogic() == null || !VALID_LOGIC.contains(audience.getLogic())) {
            errors.add("audience.logic must be AND or OR");
        }
        List<AudienceCondition> conditions = audience.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            errors.add("audience.conditions must have at least 1 condition");
            return;
        }
        if (conditions.size() > MAX_CONDITIONS) {
            errors.add("audience.conditions exceeds max " + MAX_CONDITIONS);
        }
        // v1 supports flat condition list only (no nested groups).
        // The DSL model has no nested field; nothing extra to check for depth.

        for (int i = 0; i < conditions.size(); i++) {
            AudienceCondition c = conditions.get(i);
            String prefix = "audience.conditions[" + i + "]";
            validateCondition(c, prefix, errors, warnings);
        }
    }

    /**
     * 按字段白名单检查字段存在性、启用状态、允许操作符及条件值，使用 prefix 标注错误位置。
     */
    private void validateCondition(AudienceCondition c, String prefix,
                                   List<String> errors, List<String> warnings) {
        if (c.getField() == null || c.getField().isBlank()) {
            errors.add(prefix + ".field is required");
            return;
        }
        CampaignFieldRegistry.FieldDescriptor fd = fieldRegistry.get(c.getField());
        if (fd == null) {
            errors.add(prefix + ".field '" + c.getField() + "' is not in registry");
            return;
        }
        if (!fd.isEnabled()) {
            errors.add(prefix + ".field '" + c.getField() + "' is disabled");
            return;
        }

        // operator
        if (c.getOperator() == null) {
            errors.add(prefix + ".operator is required");
            return;
        }
        Operator op;
        try {
            op = Operator.valueOf(c.getOperator());
        } catch (IllegalArgumentException e) {
            errors.add(prefix + ".operator '" + c.getOperator() + "' is not a known operator");
            return;
        }
        if (!fd.getAllowedOperators().contains(op)) {
            errors.add(prefix + ".operator " + op + " not allowed for field " + c.getField());
            return;
        }

        // valueType matches field
        if (c.getValueType() == null) {
            errors.add(prefix + ".valueType is required");
            return;
        }
        ValueType declared;
        try {
            declared = ValueType.valueOf(c.getValueType());
        } catch (IllegalArgumentException e) {
            errors.add(prefix + ".valueType '" + c.getValueType() + "' is unknown");
            return;
        }
        if (!declared.equals(fd.getValueType())) {
            errors.add(prefix + ".valueType " + declared + " does not match field type " + fd.getValueType());
            return;
        }

        // value semantics
        validateValue(c, fd, prefix, errors, warnings);
    }

    /**
     * 依据字段类型检查值格式、数值范围或枚举取值；负向标签匹配仅添加警告。
     */
    private void validateValue(AudienceCondition c, CampaignFieldRegistry.FieldDescriptor fd,
                               String prefix, List<String> errors, List<String> warnings) {
        Object value = c.getValue();
        if (value == null) {
            errors.add(prefix + ".value is required");
            return;
        }
        switch (fd.getValueType()) {
            case INTEGER -> {
                if (!(value instanceof Number) && !isParsableInteger(value)) {
                    errors.add(prefix + ".value must be integer");
                    return;
                }
                long v = toLong(value);
                if (fd.getMinimum() != null && v < fd.getMinimum().longValue()) {
                    errors.add(prefix + ".value " + v + " below min " + fd.getMinimum());
                }
                if (fd.getMaximum() != null && v > fd.getMaximum().longValue()) {
                    errors.add(prefix + ".value " + v + " above max " + fd.getMaximum());
                }
                // Field-specific business rule: activeDays7d ≤ 7 is enforced by max=7 above.
                if ("activeDays7d".equals(fd.getFieldCode()) && v > 7) {
                    errors.add(prefix + ".value activeDays7d cannot exceed 7");
                }
                if ("daysSinceLastPurchase".equals(fd.getFieldCode()) && v < 0) {
                    errors.add(prefix + ".value daysSinceLastPurchase cannot be negative");
                }
            }
            case DECIMAL -> {
                if (!(value instanceof Number) && !isParsableDecimal(value)) {
                    errors.add(prefix + ".value must be decimal");
                    return;
                }
                BigDecimal v = toBigDecimal(value);
                if (v.signum() < 0) {
                    errors.add(prefix + ".value cannot be negative for field " + c.getField());
                }
                if (fd.getMinimum() != null && v.compareTo(fd.getMinimum()) < 0) {
                    errors.add(prefix + ".value " + v + " below min " + fd.getMinimum());
                }
                if (fd.getMaximum() != null && v.compareTo(fd.getMaximum()) > 0) {
                    errors.add(prefix + ".value " + v + " above max " + fd.getMaximum());
                }
            }
            case STRING -> {
                // enum check if applicable
                if (fd.getEnumValues() != null && !fd.getEnumValues().isEmpty()
                        && !fd.getEnumValues().contains(String.valueOf(value))) {
                    errors.add(prefix + ".value not in enum " + fd.getEnumValues());
                }
            }
            case BOOLEAN -> {
                boolean b;
                if (value instanceof Boolean bb) {
                    b = bb;
                } else {
                    String s = String.valueOf(value);
                    if ("true".equalsIgnoreCase(s)) b = true;
                    else if ("false".equalsIgnoreCase(s)) b = false;
                    else {
                        errors.add(prefix + ".value must be boolean");
                        return;
                    }
                }
                // For TAG fields, value=true is represented as "1" in rule_config.
                if ("TAG".equals(fd.getSourceType()) && !b) {
                    warnings.add(prefix + " negative tag match is unusual");
                }
            }
        }
    }

    /**
     * 校验 ONCE 调度、带偏移发送时间与有效时区；缺失发送时间记为待补充字段，过去时间按一分钟容差判断。
     */
    private void validateSchedule(CampaignSchedule schedule, List<String> errors,
                                   List<String> missingFields, List<String> warnings) {
        if (schedule.getType() == null || !VALID_SCHEDULE_TYPES.contains(schedule.getType())) {
            errors.add("schedule.type must be ONCE (v1)");
        }
        if (schedule.getSendAt() == null || schedule.getSendAt().isBlank()) {
            missingFields.add("schedule.sendAt");
            return;
        }
        OffsetDateTime sendAt = parseOffsetDateTime(schedule.getSendAt());
        if (sendAt == null) {
            errors.add("schedule.sendAt must be ISO-8601 with offset, e.g. 2026-08-03T20:00:00+08:00");
            return;
        }
        // Must be in the future
        if (sendAt.isBefore(OffsetDateTime.now().minusMinutes(1))) {
            errors.add("schedule.sendAt must be in the future");
        }
        // Timezone
        if (schedule.getTimezone() == null || schedule.getTimezone().isBlank()) {
            errors.add("schedule.timezone is required");
        } else {
            try {
                ZoneId.of(schedule.getTimezone());
            } catch (DateTimeException e) {
                errors.add("schedule.timezone '" + schedule.getTimezone() + "' is not a valid ZoneId");
            }
            // Cross-check: offset must match timezone
            try {
                ZonedDateTime zdt = sendAt.atZoneSameInstant(ZoneId.of(schedule.getTimezone()));
                // Allow the offset to differ from the zone's standard offset (DST etc.)
                // but require the calendar fields to be consistent.
                if (!zdt.toOffsetDateTime().isEqual(sendAt)) {
                    warnings.add("schedule.sendAt offset does not match timezone, will normalise");
                }
            } catch (DateTimeException ignored) {
                // already caught above
            }
        }
    }

    /**
     * 要求频控次数与窗口小时数均已提供且大于零，错误累积到 errors。
     */
    private void validateFrequencyCap(FrequencyCap cap, List<String> errors) {
        if (cap.getMaxTimes() == null || cap.getMaxTimes() <= 0) {
            errors.add("frequencyCap.maxTimes must be > 0");
        }
        if (cap.getWindowHours() == null || cap.getWindowHours() <= 0) {
            errors.add("frequencyCap.windowHours must be > 0");
        }
    }

    /**
     * 判断渠道字符串是否与现有 ChannelType 枚举名称精确匹配。
     */
    private boolean isValidChannel(String channel) {
        for (ChannelType ct : ChannelType.values()) {
            if (ct.name().equals(channel)) return true;
        }
        return false;
    }

    /**
     * 解析带时区偏移的 ISO-8601 时间；格式错误时返回 null 交由上层生成校验消息。
     */
    private OffsetDateTime parseOffsetDateTime(String s) {
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 检查输入的字符串表示是否可解析为 long，不允许小数文本。
     */
    private boolean isParsableInteger(Object v) {
        try {
            Long.parseLong(String.valueOf(v));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查输入的字符串表示是否可解析为 BigDecimal。
     */
    private boolean isParsableDecimal(Object v) {
        try {
            new BigDecimal(String.valueOf(v));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 将已通过类型检查的条件值转换为 long；数值输入使用其 longValue。
     */
    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    /**
     * 把已验证的数值或文本转换为 BigDecimal，原 BigDecimal 直接复用。
     */
    private BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(String.valueOf(v));
    }
}
