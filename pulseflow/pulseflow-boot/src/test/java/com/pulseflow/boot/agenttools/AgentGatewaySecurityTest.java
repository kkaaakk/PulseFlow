package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.common.util.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentGatewaySecurityTest {
    @Test void everyReadWriteAndStreamChecksJavaOwnershipBeforeUpstreamAccess() throws Exception {
        String id = UUID.randomUUID().toString();
        AgentDraftService drafts = mock(AgentDraftService.class);
        doThrow(new CampaignForbiddenException("denied")).when(drafts).assertOwner(id, 2048L);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(2048L);
            AgentProposalGateway gateway = new AgentProposalGateway(drafts, RestClient.builder(), "http://127.0.0.1:1", "machine");
            assertThatThrownBy(() -> gateway.get(id)).isInstanceOf(CampaignForbiddenException.class);
            assertThatThrownBy(() -> gateway.followUp(id, new AgentProposalGateway.FollowUpRequest("Growth", null))).isInstanceOf(CampaignForbiddenException.class);
            assertThatThrownBy(() -> gateway.cancel(id)).isInstanceOf(CampaignForbiddenException.class);
            assertThatThrownBy(() -> gateway.events(id)).isInstanceOf(CampaignForbiddenException.class);
            assertThatThrownBy(() -> gateway.propose(id, new AgentProposalGateway.ProposalRequest("Draft", null))).isInstanceOf(CampaignForbiddenException.class);
            verify(drafts, never()).issue(anyString(), anyLong(), anyList());
            gateway.shutdown();
        }
    }

    @Test void capacityIsBoundedAndOperatorRateLimitsDoNotQueue() throws Exception {
        AgentGatewayLimit limit = new AgentGatewayLimit();
        for (int i = 0; i < 6; i++) try (AutoCloseable slot = limit.acquire(1024L)) { }
        assertThatThrownBy(() -> limit.acquire(1024L)).isInstanceOf(AgentGatewayException.class);
        AutoCloseable[] slots = new AutoCloseable[4];
        for (int i = 0; i < 4; i++) slots[i] = limit.acquire(2048L + i);
        assertThatThrownBy(() -> limit.acquire(9999L)).isInstanceOf(AgentGatewayException.class);
        for (AutoCloseable slot : slots) slot.close();
        try (AutoCloseable slot = limit.acquire(9999L)) { }
    }

    @Test void publicProjectionAndSseNeverForwardHiddenFieldsOrMachineSecrets() throws Exception {
        String id = UUID.randomUUID().toString();
        var projected = AgentProposalGateway.publicView(JsonUtil.fromJson("""
                {"id":"ok", "system_prompt":"PRIVATE_PROMPT", "model_messages":["PRIVATE_MODEL"],
                 "messages":[{"role":"ASSISTANT","content":"Safe summary","reasoning":"PRIVATE_REASON"}],
                 "proposals":[{"draft_grant":"PRIVATE_GRANT"}]}
                """, com.fasterxml.jackson.databind.JsonNode.class));
        assertThat(projected.toString()).contains("Safe summary").doesNotContain("PRIVATE_");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/internal/v1/investigations/" + id + "/events", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-PulseFlow-Agent-Token")).isEqualTo("PRIVATE_MACHINE");
            byte[] body = ("event: investigation_started\ndata: {\"investigation_id\":\"" + id
                    + "\",\"reasoning\":\"PRIVATE_REASON\"}\n\nevent: diagnosis_ready\ndata: {\"status\":\"COMPLETED\"}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AgentProposalGateway gateway = new AgentProposalGateway(mock(AgentDraftService.class), RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "PRIVATE_MACHINE");
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(1024L);
            var mvc = MockMvcBuilders.standaloneSetup(gateway).setControllerAdvice(new AgentToolExceptionHandler()).build();
            var pending = mvc.perform(get("/api/investigations/" + id + "/events")).andExpect(request().asyncStarted()).andReturn();
            pending.getAsyncResult(5000);
            String response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(response).contains("investigation_started", "diagnosis_ready").doesNotContain("PRIVATE_");
            assertThat(calls.get()).isEqualTo(1);
        } finally { gateway.shutdown(); server.stop(0); }
    }
}
