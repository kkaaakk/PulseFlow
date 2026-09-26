package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.common.util.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentProposalGatewayTest {
    @Test void onlyJavaSessionOperatorOwnsInvestigationAndDraftGrant() throws Exception {
        String id = UUID.randomUUID().toString();
        AgentDraftService drafts = mock(AgentDraftService.class);
        String grant = UUID.randomUUID() + ".PRIVATE_PROPOSE_GRANT";
        when(drafts.issue(id, 1024L, List.of())).thenReturn(grant);
        when(drafts.review(grant, id, 1024L)).thenReturn(CampaignDraft.builder()
                .id(77L).operatorId(1024L).dslJson("{\"schemaVersion\":1,\"campaignName\":\"Review\"}")
                .validationStatus("VALIDATED").estimatedAudienceCount(42L).build());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/investigations", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestHeaders().getFirst("X-PulseFlow-Agent-Token"))
                    .isEqualTo("PRIVATE_MACHINE_TOKEN");
            assertThat(body).doesNotContain("operatorId", "operator_id", "token");
            if (exchange.getRequestURI().getPath().endsWith("/proposal")) {
                assertThat(body).contains("draft_grant", "PRIVATE_PROPOSE_GRANT");
            } else {
                assertThat(body).doesNotContain("draft_grant");
            }
            byte[] response = ("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(1024L);
            AgentProposalGateway gateway = new AgentProposalGateway(drafts, RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "PRIVATE_MACHINE_TOKEN");
            assertThat(gateway.investigate(new AgentProposalGateway.Request("Investigate recall"))
                    .getData().get("id").asText()).isEqualTo(id);
            verify(drafts).registerOwner(id, 1024L);
            var response = gateway.propose(id, new AgentProposalGateway.ProposalRequest("Design a draft", List.of()));
            verify(drafts).issue(id, 1024L, List.of());
            assertThat(response.getData().draftId()).isEqualTo(77L);
            assertThat(response.getData().requiresHumanConfirmation()).isTrue();
            assertThat(JsonUtil.toJson(response)).doesNotContain("PRIVATE_");
            auth.verify(StpUtil::checkLogin, times(2));
        } finally {
            server.stop(0);
        }
    }
}
