package com.pulseflow.ai.support;

/**
 * AI task types supported by PulseFlow AI Copilot.
 *
 * <p>Each value corresponds to one structured-output LLM call and one row in
 * {@code ai_generation_record.task_type}. Used to dispatch Fake fixtures and
 * route observability metrics.</p>
 */
public enum AiTaskType {

    /** Natural language → Campaign DSL. */
    PARSE_DSL,

    /** Aggregated metrics → audience insight + strategy suggestions. */
    INSIGHT,

    /** Promotion facts + audience summary → 3 marketing content variants. */
    CONTENT,

    /** Performance summary → structured campaign review. */
    REVIEW
}
