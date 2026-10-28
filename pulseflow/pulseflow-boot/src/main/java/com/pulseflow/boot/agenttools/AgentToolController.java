package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 Agent 调用的聚合只读业务工具入口。
 * Machine-only, aggregate-only tools. Authentication is enforced by AgentToolAuthFilter. */
@RestController
@RequestMapping("/internal/v1/agent-tools")
@RequiredArgsConstructor
public class AgentToolController {
    private final AgentMetricService metrics;
    private final AgentToolService business;

    /** 接收类型化聚合查询并返回指标结果，业务校验由指标服务执行。 */
    @PostMapping("/metrics/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return metrics.query(request);
    }

    /** 提供当前期与基准期的只读指标比较接口。 */
    @PostMapping("/metrics/compare")
    public CompareResponse compare(@RequestBody CompareRequest request) {
        return metrics.compare(request);
    }

    /** 提供按单一白名单维度拆解指标的只读接口。 */
    @PostMapping("/metrics/breakdown")
    public QueryResponse breakdown(@RequestBody BreakdownRequest request) {
        return metrics.breakdown(request);
    }

    /** 按活动 ID 返回权威绩效汇总，供 Agent 建立业务证据。 */
    @GetMapping("/campaigns/{campaignId}/performance")
    public PerformanceResponse performance(@PathVariable Long campaignId) {
        return business.performance(campaignId);
    }

    /** 提供聚合归因拆解接口，避免暴露用户级事件明细。 */
    @PostMapping("/attribution/breakdown")
    public AttributionResponse attribution(@RequestBody AttributionRequest request) {
        return metrics.attribution(request);
    }

    /** 校验活动 DSL 并返回人群预估，供方案推理使用。 */
    @PostMapping("/audience/preview")
    public AudienceResponse audience(@RequestBody AudienceRequest request) {
        return business.audience(request);
    }
}
