package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.entity.UserMetricDaily;
import com.pulseflow.entity.UserTag;
import com.pulseflow.mapper.UserMetricDailyMapper;
import com.pulseflow.mapper.UserTagMapper;
import com.pulseflow.profile.tag.strategy.TagStrategy;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TagRecalcJob {

    private final UserMetricDailyMapper metricDailyMapper;
    private final UserTagMapper userTagMapper;
    private final List<TagStrategy> tagStrategies;

    @XxlJob("tagRecalcJob")
    public void execute() {
        log.info("TagRecalcJob started, {} strategies registered", tagStrategies.size());

        LocalDate weekAgo = LocalDate.now().minusDays(7);

        // 只扫描近 7 天有日桶记录的活跃用户，避免全表扫描导致 OOM。
        // selectList(null) 会把全部历史日桶拉进内存，数据量大时必崩。
        List<Long> userIds = metricDailyMapper.selectActiveUserIdsSince(weekAgo);

        for (Long userId : userIds) {
            // Get user's recent daily metrics
            List<UserMetricDaily> userMetrics = metricDailyMapper.selectList(
                    new LambdaQueryWrapper<UserMetricDaily>()
                            .eq(UserMetricDaily::getUserId, userId)
                            .ge(UserMetricDaily::getMetricDate, weekAgo));

            LocalDateTime now = LocalDateTime.now();

            for (TagStrategy strategy : tagStrategies) {
                try {
                    String tagValue = strategy.compute(userId, userMetrics);
                    if (tagValue == null) tagValue = strategy.defaultValue();

                    // uk_user_tag_calc = (user_id, tag_name, calculated_at)
                    // 设计上保留标签历史，每次重算都 insert 一条新记录。
                    // 历史清理由 DataCleanupJob 负责。
                    UserTag tag = UserTag.builder()
                            .userId(userId)
                            .tagName(strategy.getTagName())
                            .tagValue(tagValue)
                            .calculatedAt(now)
                            .build();
                    userTagMapper.insert(tag);
                } catch (Exception e) {
                    log.error("Tag calculation failed for user {}: tag={}, error={}",
                            userId, strategy.getTagName(), e.getMessage());
                }
            }
        }

        log.info("TagRecalcJob completed, processed {} users", userIds.size());
    }
}
