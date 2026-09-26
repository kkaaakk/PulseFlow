CREATE TABLE agent_investigation_owner (
    investigation_id VARCHAR(36) PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_campaign_draft_grant (
    id VARCHAR(36) PRIMARY KEY,
    token_hash CHAR(64) NOT NULL,
    investigation_id VARCHAR(36) NOT NULL,
    operator_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    authorized_facts_json JSON NOT NULL,
    draft_id BIGINT,
    proposal_hash CHAR(64),
    created_at DATETIME NOT NULL,
    KEY idx_agent_grant_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
