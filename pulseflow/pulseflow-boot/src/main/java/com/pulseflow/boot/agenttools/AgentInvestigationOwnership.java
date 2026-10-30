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

    /**
     * 校验调查 UUID 与有效操作员 ID，按 UTC 时间写入调查归属记录。
     */
    public void register(String investigationId, Long operatorId) {
        validId(investigationId);
        if (operatorId == null || operatorId <= 0) throw forbidden();
        jdbc.update("INSERT INTO agent_investigation_owner (investigation_id,operator_id,created_at) VALUES (?,?,?)",
                investigationId, operatorId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 读取调查归属；记录缺失、归属不唯一或操作员不匹配时统一拒绝访问。
     */
    public void assertOwner(String investigationId, Long operatorId) {
        validId(investigationId);
        if (operatorId == null || operatorId <= 0) throw forbidden();
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=?",
                Long.class, investigationId);
        if (owners.size() != 1 || !operatorId.equals(owners.get(0))) throw forbidden();
    }

    /**
     * 在调用方事务中使用 FOR UPDATE 锁定调查归属行，校验所有者并串行化该调查的草稿创建。
     */
    public void lockOwner(String investigationId, Long operatorId) {
        validId(investigationId);
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=? FOR UPDATE",
                Long.class, investigationId);
        if (owners.size() != 1 || !operatorId.equals(owners.get(0))) throw forbidden();
    }

    /**
     * 要求传入值能解析为 UUID；空值或格式错误统一转为 invalid_id 参数异常。
     */
    public static void validId(String id) {
        try { UUID.fromString(id); } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid_id");
        }
    }

    /**
     * 构造统一的调查归属异常，避免通过错误内容区分资源是否存在。
     */
    private static CampaignForbiddenException forbidden() {
        return new CampaignForbiddenException("investigation_forbidden");
    }
}
