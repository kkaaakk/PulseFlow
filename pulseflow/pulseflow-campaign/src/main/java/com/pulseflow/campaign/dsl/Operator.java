package com.pulseflow.campaign.dsl;

/**
 * 允许使用的比较操作符，避免模型或请求传入任意表达式。
 *
 * Comparison operators allowed in a DSL condition.
 *
 * <p>Kept aligned with {@code DecisionEngine.compare} so DSL→Rule conversion
 * is a 1:1 mapping.</p>
 */
public enum Operator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE
}
