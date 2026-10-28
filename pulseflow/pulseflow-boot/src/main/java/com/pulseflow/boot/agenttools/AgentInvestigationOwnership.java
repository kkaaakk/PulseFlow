package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.exception.CampaignForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 维护调查与操作员归属，独立于 Agent 自有存储执行访问控制。
 * Java session ownership of investigations remains independent of Agent storage. */
@Service
@RequiredArgsConstructor
public class AgentInvestigationOwnership {
    private final JdbcTemplate jdbc;

    /** 登记调查与登录操作员的归属，后续查询、追问及方案生成据此鉴权。 */
    public void register(String investigationId, Long operatorId) {
        validId(investigationId);
        if (operatorId == null || operatorId <= 0) throw forbidden();
        jdbc.update("INSERT INTO agent_investigation_owner (investigation_id,operator_id,created_at) VALUES (?,?,?)",
                investigationId, operatorId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** 验证调查 ID 与操作员归属，不匹配时拒绝访问。 */
    public void assertOwner(String investigationId, Long operatorId) {
        validId(investigationId);
        if (operatorId == null || operatorId <= 0) throw forbidden();
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=?",
                Long.class, investigationId);
        if (owners.size() != 1 || !operatorId.equals(owners.get(0))) throw forbidden();
    }

    /** 在当前事务中锁定归属记录并校验操作员，保护草稿创建期间的归属一致性。 */
    public void lockOwner(String investigationId, Long operatorId) {
        validId(investigationId);
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=? FOR UPDATE",
                Long.class, investigationId);
        if (owners.size() != 1 || !operatorId.equals(owners.get(0))) throw forbidden();
    }

    /** 验证调查 ID 格式，阻止非法标识进入路径拼接和数据库查询。 */
    public static void validId(String id) {
        try { UUID.fromString(id); } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid_id");
        }
    }

    /** 创建不泄露调查存在性和实际归属的访问拒绝异常。 */
    private static CampaignForbiddenException forbidden() {
        return new CampaignForbiddenException("investigation_forbidden");
    }
}
