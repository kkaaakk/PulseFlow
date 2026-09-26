package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.dsl.CampaignDsl;
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

/** The Java login session, not a browser-supplied operatorId, owns every proposed draft. */
@RestController
@RequestMapping("/api/investigations")
public class AgentProposalGateway {
    private final AgentDraftService drafts;
    private final String agentUrl;
    private final String internalToken;
    private final RestClient client;
    private final AgentGatewayLimit limits = new AgentGatewayLimit();
    private final ThreadPoolExecutor streams = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> { Thread t = new Thread(runnable, "agent-sse"); t.setDaemon(true); return t; });

    public AgentProposalGateway(AgentDraftService drafts, RestClient.Builder builder,
            @Value("${pulseflow.agent.service-url:}") String agentUrl,
            @Value("${pulseflow.agent.internal-token:}") String internalToken) {
        this.drafts = drafts;
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
    public record Response(String investigationId, Long draftId, String state,
                           String validationStatus, CampaignDsl dsl, Long estimatedCount,
                           String dataVersion, boolean requiresHumanConfirmation) {}

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
        AgentDraftService.validId(id);
        drafts.registerOwner(id, operator);
        return ApiResponse.success(publicView(result));
    }

    @GetMapping("/{investigationId}")
    public ApiResponse<JsonNode> get(@PathVariable String investigationId) {
        owned(investigationId);
        return ApiResponse.success(publicView(call("GET", "/" + investigationId, null)));
    }

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

    @PostMapping("/{investigationId}/cancel")
    public ApiResponse<JsonNode> cancel(@PathVariable String investigationId) {
        owned(investigationId);
        return ApiResponse.success(publicView(call("POST", "/" + investigationId + "/cancel", Map.of())));
    }

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
                                            for (String key : List.of("investigation_id", "tool_name", "evidence_id", "hypothesis_id", "status"))
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
            "evidence_added", "hypothesis_changed", "diagnosis_ready", "error");

    @PreDestroy public void shutdown() { streams.shutdownNow(); }

    private Long owned(String id) {
        StpUtil.checkLogin();
        Long operator = StpUtil.getLoginIdAsLong();
        drafts.assertOwner(id, operator);
        if (agentUrl.isBlank() || internalToken.isBlank()) throw new AgentGatewayException(503, "agent_unavailable");
        return operator;
    }

    private String url(String path) { return agentUrl.replaceAll("/$", "") + "/internal/v1/investigations" + path; }

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

    static JsonNode publicView(JsonNode result) {
        ObjectNode safe = JsonUtil.fromJson("{}", ObjectNode.class);
        for (String key : List.of("id", "goal", "status", "scope", "scope_version", "created_at", "updated_at",
                "final_diagnosis", "evidence", "hypotheses", "messages", "tool_trajectory", "proposals"))
            if (result.has(key)) safe.set(key, result.get(key).deepCopy());
        removePrivate(safe);
        return safe;
    }

    private static void removePrivate(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).remove(List.of("draft_grant", "system_prompt", "prompt", "model_messages",
                    "reasoning", "chain_of_thought", "operator_id", "operatorId", "token", "api_key"));
        }
        node.elements().forEachRemaining(AgentProposalGateway::removePrivate);
    }

    @PostMapping("/{investigationId}/proposal")
    public ApiResponse<Response> propose(@PathVariable String investigationId, @RequestBody ProposalRequest body) {
        Long operator = owned(investigationId);
        AgentDraftService.validId(investigationId);
        validate(body == null ? null : new Request(body.question()));
        List<PromotionFact> facts = body.promotionFacts() == null ? List.of() : body.promotionFacts();
        if (facts.size() > 10) throw new IllegalArgumentException("invalid_promotion_facts");
        String grant;
        try (AutoCloseable ignored = limits.acquire(operator)) {
            grant = drafts.issue(investigationId, operator, facts);
            try {
                call("POST", "/" + investigationId + "/proposal",
                        Map.of("question", body.question(), "draft_grant", grant, "promotion_facts", facts));
            } catch (AgentGatewayException upstream) {
                // Recover the authoritative Java draft when Agent persistence/response failed afterwards.
                try { drafts.review(grant, investigationId, operator); }
                catch (Exception noDraft) { throw upstream; }
            }
        } catch (AgentGatewayException error) { throw error; }
        catch (Exception ignored) { throw new AgentGatewayException(503, "agent_proposal_unavailable"); }
        CampaignDraft draft = drafts.review(grant, investigationId, operator);
        return ApiResponse.success(new Response(investigationId, draft.getId(), "DRAFT",
                draft.getValidationStatus(), JsonUtil.fromJson(draft.getDslJson(), CampaignDsl.class),
                draft.getEstimatedAudienceCount(), draft.getProfileDataVersion(), true));
    }

    private void validate(Request body) {
        if (body == null || body.question() == null || body.question().isBlank()
                || body.question().length() > 4000) throw new IllegalArgumentException("invalid_proposal_request");
        if (agentUrl.isBlank() || internalToken.isBlank()) throw new IllegalStateException("agent_unavailable");
    }
}
