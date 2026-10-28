package com.pulseflow.boot.agenttools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 供 Java 读取 Agent 方案及回写草稿关联的内部客户端。
 * Java-only access to authoritative proposals in the Agent repository. */
@Component
public class AgentProposalClient {
    private final RestClient client;
    private final String agentUrl;
    private final String token;

    /** 配置 Agent 地址、机器令牌和 HTTP 客户端，用于读取方案及回写草稿关联。 */
    public AgentProposalClient(RestClient.Builder builder,
            @Value("${pulseflow.agent.service-url:}") String agentUrl,
            @Value("${pulseflow.agent.internal-token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = builder.requestFactory(factory).build();
        this.agentUrl = agentUrl;
        this.token = token;
    }

    /** 读取指定方案的内部表示，供 Java 校验证据、归属和 DSL 后创建草稿。 */
    public JsonNode read(String proposalId) {
        ensureConfigured();
        try {
            JsonNode result = client.get().uri(url(proposalId))
                    .header("X-PulseFlow-Agent-Token", token).retrieve().body(JsonNode.class);
            if (result == null) throw new IllegalStateException();
            return result;
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) throw new AgentGatewayException(404, "proposal_not_found");
            throw new AgentGatewayException(503, "agent_unavailable");
        } catch (Exception error) {
            throw new AgentGatewayException(503, "agent_unavailable");
        }
    }

    /** 将已创建草稿 ID 回写 Agent，使方案状态与 Java 草稿关联保持一致。 */
    public void markDraftCreated(String proposalId, Long draftId) {
        ensureConfigured();
        try {
            client.put().uri(url(proposalId) + "/draft")
                    .header("X-PulseFlow-Agent-Token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("draft_id", draftId)).retrieve().toBodilessEntity();
        } catch (Exception error) {
            throw new AgentGatewayException(503, "proposal_status_update_failed");
        }
    }

    /** 确认 Agent 地址和内部令牌已配置，否则拒绝跨服务调用。 */
    private void ensureConfigured() {
        if (agentUrl.isBlank() || token.isBlank()) throw new AgentGatewayException(503, "agent_unavailable");
    }

    /** 构造内部方案接口地址，统一处理服务地址尾部斜杠。 */
    private String url(String id) {
        return agentUrl.replaceAll("/$", "") + "/internal/v1/proposals/" + id;
    }
}
