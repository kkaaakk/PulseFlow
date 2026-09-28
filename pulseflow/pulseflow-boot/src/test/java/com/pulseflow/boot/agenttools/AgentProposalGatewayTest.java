package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentProposalGatewayTest {
    @Test void javaSessionBindsProposalToOwnerWithoutCreatingDraft() throws Exception {
        String id = UUID.randomUUID().toString();
        AgentInvestigationOwnership ownership = mock(AgentInvestigationOwnership.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/investigations", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestHeaders().getFirst("X-PulseFlow-Agent-Token"))
                    .isEqualTo("PRIVATE_MACHINE_TOKEN");
            if (exchange.getRequestURI().getPath().endsWith("/proposal")) {
                assertThat(body).contains("owner_id", "1024", "promotion_facts");
            } else {
                assertThat(body).doesNotContain("owner_id");
            }
            byte[] response = ("{\"id\":\"" + id + "\",\"proposals\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(1024L);
            AgentProposalGateway gateway = new AgentProposalGateway(ownership, RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "PRIVATE_MACHINE_TOKEN");
            assertThat(gateway.investigate(new AgentProposalGateway.Request("Investigate recall"))
                    .getData().get("id").asText()).isEqualTo(id);
            verify(ownership).register(id, 1024L);
            assertThat(gateway.propose(id, new AgentProposalGateway.ProposalRequest("Design proposal", List.of()))
                    .getData().get("proposals").isArray()).isTrue();
            verify(ownership).assertOwner(id, 1024L);
            gateway.shutdown();
        } finally { server.stop(0); }
    }
}
