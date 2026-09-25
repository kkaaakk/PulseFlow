package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Machine-only, aggregate-only tools. Authentication is enforced by AgentToolAuthFilter. */
@RestController
@RequestMapping("/internal/v1/agent-tools")
@RequiredArgsConstructor
public class AgentToolController {
    private final AgentMetricService metrics;
    private final AgentToolService business;

    @PostMapping("/metrics/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return metrics.query(request);
    }

    @PostMapping("/metrics/compare")
    public CompareResponse compare(@RequestBody CompareRequest request) {
        return metrics.compare(request);
    }

    @PostMapping("/metrics/breakdown")
    public QueryResponse breakdown(@RequestBody BreakdownRequest request) {
        return metrics.breakdown(request);
    }

    @GetMapping("/campaigns/{campaignId}/performance")
    public PerformanceResponse performance(@PathVariable Long campaignId) {
        return business.performance(campaignId);
    }

    @PostMapping("/attribution/breakdown")
    public AttributionResponse attribution(@RequestBody AttributionRequest request) {
        return metrics.attribution(request);
    }

    @PostMapping("/audience/preview")
    public AudienceResponse audience(@RequestBody AudienceRequest request) {
        return business.audience(request);
    }
}
