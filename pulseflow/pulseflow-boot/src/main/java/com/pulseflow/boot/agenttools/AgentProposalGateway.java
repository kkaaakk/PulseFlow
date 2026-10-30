package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.campaign.dsl.PromotionFact;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.common.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;

import java.util.Map;
import java.util.List;

/** Java session gateway for Agent investigations and persisted proposals. */
@RestController
@RequestMapping("/api/investigations")
public class AgentProposalGateway {
    private final AgentInvestigationOwnership ownership;
    private final String agentUrl;
    private final String internalToken;
    private final RestClient client;
    private final AgentGatewayLimit limits = new AgentGatewayLimit();
    private final ThreadPoolExecutor streams = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> { Thread t = new Thread(runnable, "agent-sse"); t.setDaemon(true); return t; });

    /**
     * 保存调查归属服务与内部调用凭据，创建五秒连接、两分钟读取超时的 Agent HTTP 客户端。
     */
    public AgentProposalGateway(AgentInvestigationOwnership ownership, RestClient.Builder builder,
            @Value("${pulseflow.agent.service-url:}") String agentUrl,
            @Value("${pulseflow.agent.internal-token:}") String internalToken) {
        this.ownership = ownership;
        this.agentUrl = agentUrl;
        this.internalToken = internalToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        this.client = builder.requestFactory(factory).build();
    }

    public record Request(String question) {}
    public record FollowUpRequest(String question, String scope) {}
    public record ProposalRequest(String question, List<PromotionFact> promotionFacts) {}

    /**
     * 校验登录、问题长度及容量配额，启动后台调查后登记当前操作员归属，再返回过滤后的公开视图。
     */
    @PostMapping
    public ApiResponse<JsonNode> investigate(@RequestBody Request body) {
        StpUtil.checkLogin();
        Long operator = StpUtil.getLoginIdAsLong();
        validate(body);
        JsonNode result;
        try (AutoCloseable ignored = limits.acquire(operator)) {
            result = call("POST", "/start", Map.of("question", body.question()));
        } catch (AgentGatewayException error) { throw error; }
        catch (Exception ignored) { throw new AgentGatewayException(503, "agent_unavailable"); }
        if (result == null || !result.hasNonNull("id")) throw new IllegalStateException("agent_unavailable");
        String id = result.get("id").asText();
        AgentInvestigationOwnership.validId(id);
        ownership.register(id, operator);
        return ApiResponse.success(publicView(result));
    }

    /**
     * 验证当前用户拥有指定调查，再读取并过滤 Agent 的调查快照。
     */
    @GetMapping("/{investigationId}")
    public ApiResponse<JsonNode> get(@PathVariable String investigationId) {
        owned(investigationId);
        return ApiResponse.success(publicView(call("GET", "/" + investigationId, null)));
    }

    /**
     * 校验调查归属、问题及可选范围，在配额保护下恢复后台调查；范围非空时限制为一千字符。
     */
    @PostMapping("/{investigationId}/follow-up")
    public ApiResponse<JsonNode> followUp(@PathVariable String investigationId, @RequestBody FollowUpRequest body) {
        Long operator = owned(investigationId);
        validate(body == null ? null : new Request(body.question()));
        if (body.scope() != null && (body.scope().isBlank() || body.scope().length() > 1000))
            throw new IllegalArgumentException("invalid_scope");
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("question", body.question());
        if (body.scope() != null) payload.put("scope", body.scope());
        try (AutoCloseable ignored = limits.acquire(operator)) {
            return ApiResponse.success(publicView(call("POST", "/" + investigationId + "/resume", payload)));
        } catch (AgentGatewayException error) { throw error; }
        catch (Exception ignored) { throw new AgentGatewayException(503, "agent_unavailable"); }
    }

    /**
     * 校验调查归属后请求 Agent 取消运行，并返回取消后的公开调查状态。
     */
    @PostMapping("/{investigationId}/cancel")
    public ApiResponse<JsonNode> cancel(@PathVariable String investigationId) {
        owned(investigationId);
        return ApiResponse.success(publicView(call("POST", "/" + investigationId + "/cancel", Map.of())));
    }

    /**
     * 代理调查 SSE，只转发白名单事件和标识字段；限制单行长度、连接时长和八路并发，不向浏览器透传模型内容。
     */
    @GetMapping(value = "/{investigationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String investigationId) {
        owned(investigationId);
        SseEmitter emitter = new SseEmitter(115000L);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(ignored -> closed.set(true));
        try {
            streams.execute(() -> {
                try {
                    client.get().uri(url("/" + investigationId + "/events"))
                            .header("X-PulseFlow-Agent-Token", internalToken)
                            .exchange((request, response) -> {
                                if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException();
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                        response.getBody(), StandardCharsets.UTF_8))) {
                                    String event = null;
                                    String line;
                                    while (!closed.get() && (line = reader.readLine()) != null) {
                                        if (line.length() > 2048) throw new IllegalStateException();
                                        if (line.startsWith("event: ")) event = line.substring(7);
                                        if (line.startsWith("data: ") && event != null && EVENTS.contains(event)) {
                                            JsonNode data = JsonUtil.fromJson(line.substring(6), JsonNode.class);
                                            ObjectNode safe = JsonUtil.fromJson("{}", ObjectNode.class);
                                            for (String key : List.of("investigation_id", "tool_name", "evidence_id", "hypothesis_id", "proposal_id", "status"))
                                                if (data.has(key) && data.get(key).isTextual()) safe.set(key, data.get(key));
                                            emitter.send(SseEmitter.event().name(event).data(safe));
                                            event = null;
                                        }
                                    }
                                }
                                return null;
                            });
                    emitter.complete();
                } catch (Exception ignored) {
                    try { emitter.send(SseEmitter.event().name("error").data(Map.of("status", "agent_unavailable"))); }
                    catch (Exception suppressed) { /* disconnected browser */ }
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException ignored) { throw new AgentGatewayException(429, "agent_capacity_exceeded"); }
        return emitter;
    }

    private static final Set<String> EVENTS = Set.of("investigation_started", "tool_started", "tool_completed",
            "evidence_added", "hypothesis_changed", "proposal_ready", "diagnosis_ready", "error");

    /**
     * 应用关闭时立即中断 SSE 工作线程，释放流式代理资源。
     */
    @PreDestroy public void shutdown() { streams.shutdownNow(); }

    /**
     * 校验登录与调查所有权，并要求 Agent 地址和内部令牌已配置；返回已验证的操作员 ID。
     */
    private Long owned(String id) {
        StpUtil.checkLogin();
        Long operator = StpUtil.getLoginIdAsLong();
        ownership.assertOwner(id, operator);
        if (agentUrl.isBlank() || internalToken.isBlank()) throw new AgentGatewayException(503, "agent_unavailable");
        return operator;
    }

    /**
     * 以固定内部调查路径拼接 Agent 地址与相对资源路径。
     */
    private String url(String path) { return agentUrl.replaceAll("/$", "") + "/internal/v1/investigations" + path; }

    /**
     * 携带内部令牌调用 Agent 并要求非空 JSON；将已知状态映射为稳定业务错误，其余故障统一返回 503。
     */
    private JsonNode call(String method, String path, Object payload) {
        try {
            var request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url(path))
                    .header("X-PulseFlow-Agent-Token", internalToken).contentType(MediaType.APPLICATION_JSON);
            if (payload != null) request.body(payload);
            JsonNode result = request.retrieve().body(JsonNode.class);
            if (result == null) throw new IllegalStateException();
            return result;
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            String code = switch (status) {
                case 404 -> "not_found"; case 409 -> "investigation_busy";
                case 422 -> "investigation_blocked"; case 429 -> "agent_capacity_exceeded";
                default -> "agent_unavailable";
            };
            throw new AgentGatewayException(Set.of(404, 409, 422, 429).contains(status) ? status : 503, code);
        } catch (Exception ignored) { throw new AgentGatewayException(503, "agent_unavailable"); }
    }

    /**
     * 复制调查的公开顶层字段，再递归删除私有字段；原始 Agent 响应保持不变。
     */
    static JsonNode publicView(JsonNode result) {
        ObjectNode safe = JsonUtil.fromJson("{}", ObjectNode.class);
        for (String key : List.of("id", "goal", "status", "scope", "scope_version", "created_at", "updated_at",
                "final_diagnosis", "evidence", "hypotheses", "messages", "tool_trajectory", "proposals"))
            if (result.has(key)) safe.set(key, result.get(key).deepCopy());
        removePrivate(safe);
        return safe;
    }

    /**
     * 递归遍历对象和数组，删除提示词、推理、归属标识及凭据字段。
     */
    private static void removePrivate(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).remove(List.of("system_prompt", "prompt", "model_messages",
                    "reasoning", "chain_of_thought", "operator_id", "operatorId",
                    "owner_id", "ownerId", "token", "api_key"));
        }
        node.elements().forEachRemaining(AgentProposalGateway::removePrivate);
    }

    /**
     * 校验调查归属和问题，仅传递操作员授权的至多十条促销事实，在调用配额内请求生成提案。
     */
    @PostMapping("/{investigationId}/proposal")
    public ApiResponse<JsonNode> propose(@PathVariable String investigationId, @RequestBody ProposalRequest body) {
        Long operator = owned(investigationId);
        AgentInvestigationOwnership.validId(investigationId);
        validate(body == null ? null : new Request(body.question()));
        List<PromotionFact> facts = body.promotionFacts() == null ? List.of() : body.promotionFacts();
        if (facts.size() > 10) throw new IllegalArgumentException("invalid_promotion_facts");
        try (AutoCloseable ignored = limits.acquire(operator)) {
            return ApiResponse.success(publicView(call("POST", "/" + investigationId + "/proposal",
                    Map.of("question", body.question(), "owner_id", operator, "promotion_facts", facts))));
        } catch (AgentGatewayException error) { throw error; }
        catch (Exception ignored) { throw new AgentGatewayException(503, "agent_proposal_unavailable"); }
    }

    /**
     * 要求问题非空且不超过四千字符，并在调用 Agent 前检查内部服务配置。
     */
    private void validate(Request body) {
        if (body == null || body.question() == null || body.question().isBlank()
                || body.question().length() > 4000) throw new IllegalArgumentException("invalid_proposal_request");
        if (agentUrl.isBlank() || internalToken.isBlank()) throw new IllegalStateException("agent_unavailable");
    }
}
