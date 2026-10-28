package com.pulseflow.campaign.content;

import com.pulseflow.campaign.content.ContentResult;
import com.pulseflow.campaign.content.ContentVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 CampaignContentValidator 的业务行为和边界条件。
 *
 * Pure unit tests for {@link CampaignContentValidator}.
 *
 * <p>Covers the design §9.3 guardrails: length limits, forbidden words,
 * fabricated numbers, fabricated urgency, PII, template variables, and
 * duplicate-type dropping.</p>
 */
class CampaignContentValidatorTest {

    private final CampaignContentValidator validator = new CampaignContentValidator();

    /** 构造包含授权优惠和校验约束的文案输入夹具。 */
    private Map<String, Object> baseInput() {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("titleMaxLength", 24);
        input.put("bodyMaxLength", 80);
        // Promotion facts: 满300减30, valid until 2026-08-05
        input.put("promotionFacts", List.of(
                Map.of("type", "FULL_REDUCTION", "threshold", 300, "discount", 30,
                        "validUntil", "2026-08-05", "description", "满300减30")));
        input.put("validUntil", "2026-08-05");
        return input;
    }

    /** 生成指定类型的候选文案，供长度、事实和内容测试复用。 */
    private ContentVariant variant(String type, String title, String body) {
        return ContentVariant.builder()
                .type(type).title(title).body(body).strategy("s")
                .build();
    }

    /** 验证三个合法文案变体均可通过校验。 */
    @Test
    @DisplayName("three valid variants with distinct types pass")
    void threeValidVariants() {
        Map<String, Object> input = baseInput();
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "购物车好物满300减30", "你关注的商品还在购物车中，满300减30优惠已开放。"),
                        variant("URGENCY", "满减优惠即将结束", "购物车商品仍可购买，满300减30优惠有效至8月5日。"),
                        variant("PERSONALIZED", "你关注的好物有新优惠", "近期关注的商品可享满300减30，点击查看当前优惠。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(3);
    }

    /** 验证标题超过长度上限时被拒绝。 */
    @Test
    @DisplayName("title exceeding maxLength is dropped")
    void titleTooLong() {
        Map<String, Object> input = baseInput();
        String longTitle = "这是一个非常非常非常非常非常非常非常非常非常非常非常非常长的标题超过限制";
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", longTitle, "正文"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。"),
                        variant("PERSONALIZED", "好物推荐", "近期关注的商品可享满300减30。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(2);
        assertThat(validated.getVariants()).extracting(ContentVariant::getType)
                .containsExactlyInAnyOrder("URGENCY", "PERSONALIZED");
    }

    /** 验证正文超过长度上限时被拒绝。 */
    @Test
    @DisplayName("body exceeding maxLength is dropped")
    void bodyTooLong() {
        Map<String, Object> input = baseInput();
        input.put("bodyMaxLength", 10);
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "短标题", "这段正文明显超过了十个字符的限制长度"),
                        variant("URGENCY", "短标题", "正好十个字以内")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("URGENCY");
    }

    /** 验证文案中虚构的优惠数字被识别并拒绝。 */
    @Test
    @DisplayName("fabricated discount number (全场7折) is dropped")
    void fabricatedDiscount() {
        Map<String, Object> input = baseInput();
        // input has 300, 30, and validUntil-derived numbers (2026, 8, 5).
        // "7折" → 7 is NOT in allowed numbers → variant must be dropped.
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "全场7折优惠", "现在下单可享7折优惠"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        // DIRECT_BENEFIT references 7 which is not in allowedNumbers
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("URGENCY");
    }

    /** 验证没有事实支持的紧迫性描述被拒绝。 */
    @Test
    @DisplayName("fabricated urgency phrase '最后一天' is dropped when input has no stockLimited flag")
    void fabricatedUrgency() {
        Map<String, Object> input = baseInput();
        // No stockLimited flag, no "最后一天" in input → should be dropped
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("URGENCY", "最后一天优惠", "满300减30优惠最后一天有效。"),
                        variant("DIRECT_BENEFIT", "满300减30", "满300减30优惠已开放。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("DIRECT_BENEFIT");
    }

    /** 验证有库存限制事实支持时允许相应紧迫性描述。 */
    @Test
    @DisplayName("stockLimited flag allows urgency phrases")
    void stockLimitedAllowsUrgency() {
        Map<String, Object> input = baseInput();
        input.put("stockLimited", true);
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("URGENCY", "限量优惠", "满300减30优惠限量开放。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
    }

    /** 验证命中禁用词的文案不能通过校验。 */
    @Test
    @DisplayName("forbidden word causes variant to be dropped")
    void forbiddenWord() {
        Map<String, Object> input = baseInput();
        input.put("forbiddenWords", List.of("竞品名称"));
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "竞品名称满300减30", "满300减30优惠已开放。"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("URGENCY");
    }

    /** 验证未处理的模板变量不会作为有效文案通过。 */
    @Test
    @DisplayName("un-substituted template variable is dropped")
    void templateVariable() {
        Map<String, Object> input = baseInput();
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "用户{{name}}优惠", "满300减30已开放。"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("URGENCY");
    }

    /** 验证正文中的敏感个人信息受到内容校验约束。 */
    @Test
    @DisplayName("PII (phone number) causes variant to be dropped")
    void piiInBody() {
        Map<String, Object> input = baseInput();
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "联系13812345678", "满300减30已开放。"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("URGENCY");
    }

    /** 验证重复文案类型不会被当成多个有效变体。 */
    @Test
    @DisplayName("duplicate types: second occurrence is dropped")
    void duplicateTypes() {
        Map<String, Object> input = baseInput();
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "满300减30优惠", "满300减30已开放。"),
                        variant("DIRECT_BENEFIT", "再来一个直白型", "满300减30已开放。"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(2);
    }

    /** 验证所有候选无效时结果明确失败。 */
    @Test
    @DisplayName("all variants invalid throws IllegalArgumentException")
    void allInvalid() {
        Map<String, Object> input = baseInput();
        // 7折 references 7 which is not in allowed numbers
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("DIRECT_BENEFIT", "全场7折", "现在下单可享7折优惠")))
                .build();
        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("All content variants failed");
    }

    /** 验证缺失候选列表时安全返回校验失败。 */
    @Test
    @DisplayName("null variants throws")
    void nullVariants() {
        Map<String, Object> input = baseInput();
        assertThatThrownBy(() -> validator.validate(input, new ContentResult()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 验证不支持的文案变体类型被拒绝。 */
    @Test
    @DisplayName("invalid type is dropped")
    void invalidType() {
        Map<String, Object> input = baseInput();
        ContentResult result = ContentResult.builder()
                .variants(List.of(
                        variant("SPAM", "满300减30", "满300减30已开放。"),
                        variant("URGENCY", "满减即将结束", "满300减30优惠有效至8月5日。")))
                .build();
        ContentResult validated = validator.validate(input, result);
        assertThat(validated.getVariants()).hasSize(1);
        assertThat(validated.getVariants().get(0).getType()).isEqualTo("URGENCY");
    }
}
