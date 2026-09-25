package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.PerformanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentToolAuthTest {
    private final AgentMetricService metrics = mock(AgentMetricService.class);
    private final AgentToolService business = mock(AgentToolService.class);
    private final AgentToolController controller = new AgentToolController(metrics, business);

    private MockMvc mvc(String configuredToken) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentToolExceptionHandler())
                .addFilters(new AgentToolAuthFilter(configuredToken))
                .build();
    }

    @Test
    void missingAndWrongTokenAreRejectedBeforeBusinessCode() throws Exception {
        MockMvc mvc = mvc("a-long-internal-machine-token");
        mvc.perform(get("/internal/v1/agent-tools/campaigns/7/performance"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/agent-tools/campaigns/7/performance")
                .header(AgentToolAuthFilter.HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(metrics, business);
    }

    @Test
    void correctTokenPassesAndUnconfiguredApiFailsClosed() throws Exception {
        when(business.performance(7L)).thenReturn(new PerformanceResponse(
                AgentMetricService.metadata("campaign-summary", List.of()), 7L, false,
                null, null, null, null, null, null, null, null, null));
        mvc("a-long-internal-machine-token")
                .perform(get("/internal/v1/agent-tools/campaigns/7/performance")
                        .header(AgentToolAuthFilter.HEADER, "a-long-internal-machine-token"))
                .andExpect(status().isOk());
        mvc("").perform(get("/internal/v1/agent-tools/campaigns/7/performance")
                        .header(AgentToolAuthFilter.HEADER, "anything"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownMetricAndDimensionReturnBadRequestWithoutEchoingInput() throws Exception {
        String body = """
                {"metric":"UNKNOWN_PRIVATE_VALUE","timeRange":{
                  "fromInclusive":"2026-09-01T00:00:00+08:00",
                  "toExclusive":"2026-09-02T00:00:00+08:00"},
                  "dimensions":["REGION"]}
                """;
        mvc("a-long-internal-machine-token")
                .perform(post("/internal/v1/agent-tools/metrics/query")
                        .header(AgentToolAuthFilter.HEADER, "a-long-internal-machine-token")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("UNKNOWN_PRIVATE_VALUE"));
        verifyNoInteractions(metrics, business);
    }
}
