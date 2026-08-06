package com.pulseflow.ai.domain.campaign;

/**
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
