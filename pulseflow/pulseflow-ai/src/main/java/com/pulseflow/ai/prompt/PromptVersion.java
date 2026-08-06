package com.pulseflow.ai.prompt;

/**
 * Prompt version identifiers written to ai_generation_record.prompt_version
 * so we can correlate AI output quality with prompt revisions.
 */
public final class PromptVersion {

    public static final String CAMPAIGN_INTENT   = "campaign-intent-v1";
    public static final String AUDIENCE_INSIGHT  = "audience-insight-v1";
    public static final String CAMPAIGN_CONTENT  = "campaign-content-v1";
    public static final String CAMPAIGN_REVIEW   = "campaign-review-v1";

    private PromptVersion() {
        throw new UnsupportedOperationException("Constant class");
    }
}
