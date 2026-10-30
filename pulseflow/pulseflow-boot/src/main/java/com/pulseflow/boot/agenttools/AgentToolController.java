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

    /**
     * 接收有界时间段与过滤条件，返回 Java 权威口径的聚合指标及来源元数据。
     */
    @PostMapping("/metrics/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return metrics.query(request);
    }

    /**
     * 接收两个时间窗口的指标比较请求，返回由 Java 计算的绝对变化和相对变化。
     */
    @PostMapping("/metrics/compare")
    public CompareResponse compare(@RequestBody CompareRequest request) {
        return metrics.compare(request);
    }

    /**
     * 按受支持的单一维度拆解指标；维度、过滤值与行数限制交由指标服务校验。
     */
    @PostMapping("/metrics/breakdown")
    public QueryResponse breakdown(@RequestBody BreakdownRequest request) {
        return metrics.breakdown(request);
    }

    /**
     * 只读获取指定 Campaign 的预计算绩效快照，缺少快照时通过 available 与警告表达。
     */
    @GetMapping("/campaigns/{campaignId}/performance")
    public PerformanceResponse performance(@PathVariable Long campaignId) {
        return business.performance(campaignId);
    }

    /**
     * 按请求维度查询归因聚合结果，避免向 Agent 暴露逐用户归因明细。
     */
    @PostMapping("/attribution/breakdown")
    public AttributionResponse attribution(@RequestBody AttributionRequest request) {
        return metrics.attribution(request);
    }

    /**
     * 校验 Campaign DSL 并返回人群规模预估，结果仅包含汇总信息与稳定警告码。
     */
    @PostMapping("/audience/preview")
    public AudienceResponse audience(@RequestBody AudienceRequest request) {
        return business.audience(request);
    }
}
