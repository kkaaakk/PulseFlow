package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.exception.CampaignForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Java session ownership of investigations remains independent of Agent storage. */
@Service
@RequiredArgsConstructor
public class AgentInvestigationOwnership {
    private final JdbcTemplate jdbc;

    public void register(String investigationId, Long operatorId) {
        validId(investigationId);
        if (operatorId == null || operatorId <= 0) throw forbidden();
        jdbc.update("INSERT INTO agent_investigation_owner (investigation_id,operator_id,created_at) VALUES (?,?,?)",
                investigationId, operatorId, LocalDateTime.now(ZoneOffset.UTC));
    }

    public void assertOwner(String investigationId, Long operatorId) {
        validId(investigationId);
        if (operatorId == null || operatorId <= 0) throw forbidden();
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=?",
                Long.class, investigationId);
        if (owners.size() != 1 || !operatorId.equals(owners.get(0))) throw forbidden();
    }

    public void lockOwner(String investigationId, Long operatorId) {
        validId(investigationId);
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=? FOR UPDATE",
                Long.class, investigationId);
        if (owners.size() != 1 || !operatorId.equals(owners.get(0))) throw forbidden();
    }

    public static void validId(String id) {
        try { UUID.fromString(id); } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid_id");
        }
    }

    private static CampaignForbiddenException forbidden() {
        return new CampaignForbiddenException("investigation_forbidden");
    }
}
