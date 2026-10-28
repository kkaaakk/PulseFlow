package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.campaign.analytics.PerformanceSummaryCalculator;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定期计算活动绩效快照，供控制台和 Agent 查询。
 * Persists deterministic delivery, click and attribution metrics for finished campaigns. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignPerformanceSummaryJob {
    private final CampaignMapper campaignMapper;
    private final PerformanceSummaryCalculator calculator;

    /** 批量更新活动绩效汇总，供控制台和 Agent 只读工具查询。 */
    @XxlJob("campaignPerformanceSummaryJob")
    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        List<Campaign> campaigns = campaignMapper.selectList(new LambdaQueryWrapper<Campaign>()
                .isNotNull(Campaign::getEndTime)
                .lt(Campaign::getEndTime, now)
                .ge(Campaign::getEndTime, now.minusHours(72))
                .orderByAsc(Campaign::getEndTime));
        for (Campaign campaign : campaigns) {
            try {
                calculator.compute(campaign.getId());
            } catch (Exception e) {
                log.error("Performance summary failed for campaign {}", campaign.getId(), e);
            }
        }
    }
}
