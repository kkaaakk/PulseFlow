package com.pulseflow.boot.agenttools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Java-only access to authoritative proposals in the Agent repository. */
@Component
public class AgentProposalClient {
    private final RestClient client;
    private final String agentUrl;
    private final String token;

    /**
     * 创建访问 Agent 提案接口的客户端，设置三秒连接超时、十秒读取超时并保存内部鉴权配置。
     */
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

    /**
     * 按提案 ID 读取 Agent 快照；404 映射为提案不存在，空响应及其他调用失败统一映射为服务不可用。
     */
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

    /**
     * 用内部令牌将已提交的 Java 草稿 ID 回写至提案；失败抛出状态同步异常以便后续重试。
     */
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

    /**
     * 要求 Agent 地址和内部令牌均已配置，否则在发起网络请求前返回服务不可用。
     */
    private void ensureConfigured() {
        if (agentUrl.isBlank() || token.isBlank()) throw new AgentGatewayException(503, "agent_unavailable");
    }

    /**
     * 去掉服务地址末尾的斜杠，拼接内部提案资源地址；ID 格式由上游边界校验。
     */
    private String url(String id) {
        return agentUrl.replaceAll("/$", "") + "/internal/v1/proposals/" + id;
    }
}
