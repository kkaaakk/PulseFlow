package com.pulseflow.ai.domain.campaign;

/**
 * Lifecycle states for an AI Campaign draft.
 *
 * <pre>
 * GENERATED  ──► VALIDATED ──► CONFIRMED
 *    │             │
 *    │             ▼
 *    └──────► NEEDS_CONFIRMATION
 *                 │
 *                 ▼
 *               INVALID
 *
 * Any non-terminal state may transition to EXPIRED when expires_at passes.
 * </pre>
 *
 * <p>Only {@code VALIDATED} drafts may enter the confirm flow.</p>
 */
public enum DraftStatus {

    /** AI returned a draft; validation has not yet been attempted. */
    GENERATED,

    /** AI returned incomplete input (missing time / promotion); operator must fill. */
    NEEDS_CONFIRMATION,

    /** Full validation passed; ready for audience preview and confirmation. */
    VALIDATED,

    /** Validation failed; operator must edit and re-save. */
    INVALID,

    /** Confirmed and converted to a real campaign; confirmed_campaign_id is set. */
    CONFIRMED,

    /** expires_at passed before confirmation. */
    EXPIRED
}
