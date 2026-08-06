package com.pulseflow.campaign.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.campaign.delay.DelayedTaskManager;
import com.pulseflow.campaign.delivery.DeliveryService;
import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.common.util.DedupKeyUtil;
import com.pulseflow.common.util.DelayedTaskConstants;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import com.pulseflow.profile.service.ProfileService;
import com.pulseflow.profile.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionEngine {

    private final CampaignMapper campaignMapper;
    private final CampaignRuleMapper campaignRuleMapper;
    private final ProfileService profileService;
    private final DeliveryService deliveryService;
    private final DelayedTaskManager delayedTaskManager;
    private final UserPreferenceService userPreferenceService;

    /** Delayed task type used for the Redis ZSET namespace. */
    private static final String DELAYED_TASK_TYPE = DelayedTaskConstants.CAMPAIGN_TASK_TYPE;

    /**
     * Evaluate an event against matching active campaigns.
     * Handles both EVENT (immediate) and DELAYED (scheduled) trigger types.
     * Called from EventConsumer Phase 3.
     *
     * <p><b>异常传播契约</b>：业务跳过（规则不匹配、预过滤不通过、dedup 命中）
     * 在内部消化；基础设施异常（DB / Redis / Kafka 调用失败）必须向外抛——
     * 否则 EventConsumer 会认为 Phase 3 成功，不写补偿任务，导致决策丢失。
     * 这与简历亮点"Redis/决策失败后补偿恢复"直接相关。</p>
     */
    @Transactional
    public void evaluate(Map<String, Object> eventMap) {
        String eventType = (String) eventMap.get("eventType");
        Long userId = toLong(eventMap.get("userId"));
        String eventId = (String) eventMap.get("eventId");

        // ---- EVENT (immediate) campaigns ----
        List<Campaign> immediateCampaigns = campaignMapper.selectList(
                new LambdaQueryWrapper<Campaign>()
                        .eq(Campaign::getStatus, CampaignStatus.ACTIVE.name())
                        .eq(Campaign::getTriggerType, TriggerType.EVENT.name())
                        .apply("FIND_IN_SET({0}, event_types) > 0", eventType));

        for (Campaign campaign : immediateCampaigns) {
            try {
                if (!inActiveWindow(campaign)) continue;

                List<CampaignRule> rules = loadRules(campaign.getId());
                if (allRulesMatched(rules, userId, eventMap)) {
                    // Quick pre-filter: DND / unsubscribed / already converted.
                    if (!userPreferenceService.canDeliver(
                            userId, campaign.getId(), campaign.getChannel())) {
                        continue;
                    }

                    String dedupKey = DedupKeyUtil.forEvent(campaign.getId(), userId, eventId);

                    DeliveryTaskDto dto = DeliveryTaskDto.builder()
                            .campaignId(campaign.getId())
                            .userId(userId)
                            .dedupKey(dedupKey)
                            .triggerEventId(eventId)
                            .channel(campaign.getChannel())
                            .messageContent(campaign.getMessageTemplate())
                            .createdAt(LocalDateTime.now())
                            .build();

                    deliveryService.createDeliveryTask(dto);
                    log.info("EVENT rule matched: campaign={}, userId={}, eventId={}",
                            campaign.getId(), userId, eventId);
                }
            } catch (Exception e) {
                // 业务异常（dedup 命中 / 应用层 PulseFlowException）记录后继续下一个活动；
                // 基础设施异常（DB/Redis/Kafka 失败，典型如 DataAccessException、
                // RedisException、KafkaException）必须 re-throw，让 EventConsumer 写补偿任务。
                if (isBusinessException(e)) {
                    log.info("Decision evaluation skipped for campaign {}: {}",
                            campaign.getId(), e.getMessage());
                    continue;
                }
                log.error("Decision evaluation infrastructure failure for campaign {}: {}",
                        campaign.getId(), e.getMessage(), e);
                throw e;
            }
        }

        // ---- DELAYED campaigns (e.g. cart abandon) ----
        // Schedule a delayed task; actual delivery happens after delay_seconds
        // when DelayedTaskExecutor checks the condition (item still in cart).
        List<Campaign> delayedCampaigns = campaignMapper.selectList(
                new LambdaQueryWrapper<Campaign>()
                        .eq(Campaign::getStatus, CampaignStatus.ACTIVE.name())
                        .eq(Campaign::getTriggerType, TriggerType.DELAYED.name())
                        .apply("FIND_IN_SET({0}, event_types) > 0", eventType));

        for (Campaign campaign : delayedCampaigns) {
            try {
                if (!inActiveWindow(campaign)) continue;

                Map<String, Object> props = getProperties(eventMap);
                String cartItemId = props != null ? String.valueOf(props.getOrDefault("cartItemId", "")) : "";
                if (cartItemId.isEmpty()) {
                    log.warn("DELAYED campaign {} matched event {} but no cartItemId in properties, skip",
                            campaign.getId(), eventId);
                    continue;
                }

                int delaySeconds = campaign.getDelaySeconds() != null ? campaign.getDelaySeconds() : 0;
                if (delaySeconds <= 0) {
                    log.warn("DELAYED campaign {} has non-positive delay_seconds, skip", campaign.getId());
                    continue;
                }

                // taskId encodes everything needed later; equals the delayed dedup key
                // so ZADD naturally de-duplicates reschedules of the same cart item.
                String taskId = DedupKeyUtil.forDelayed(
                        campaign.getId(), userId, cartItemId, eventId);
                long executeAtMillis = System.currentTimeMillis() + delaySeconds * 1000L;

                delayedTaskManager.addDelayedTask(DELAYED_TASK_TYPE, taskId, executeAtMillis);
                log.info("DELAYED task scheduled: campaign={}, userId={}, cartItemId={}, executeAt={}",
                        campaign.getId(), userId, cartItemId, executeAtMillis);
            } catch (Exception e) {
                if (isBusinessException(e)) {
                    log.info("Delayed scheduling skipped for campaign {}: {}",
                            campaign.getId(), e.getMessage());
                    continue;
                }
                log.error("Delayed scheduling infrastructure failure for campaign {}: {}",
                        campaign.getId(), e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * 判断异常是否为业务可跳过类型。
     * - DuplicateKeyException：dedup_key 命中，幂等跳过
     * - PulseFlowException：应用层业务异常
     * 其余视为基础设施异常（DB/Redis/Kafka 故障），必须抛出触发补偿。
     */
    private boolean isBusinessException(Exception e) {
        return e instanceof DuplicateKeyException
                || e instanceof PulseFlowException
                || e instanceof IllegalArgumentException;
    }

    /**
     * Evaluate for delayed/scheduled campaigns (batch mode).
     * Creates delivery tasks for matched users without event context.
     * Used by CampaignSelectionJob for SCHEDULED campaigns (Phase B execution).
     *
     * <p>异常传播契约同 {@link #evaluate}：业务跳过内部消化，基础设施异常向外抛，
     * 由 CampaignSelectionJob 的 catch 将 execution 标回 PENDING 触发重试。</p>
     */
    @Transactional
    public void evaluateBatch(Campaign campaign, Long userId, Long campaignExecutionId) {
        try {
            List<CampaignRule> rules = loadRules(campaign.getId());
            if (!allRulesMatched(rules, userId, Collections.emptyMap())) {
                return;
            }

            // Quick pre-filter: DND / unsubscribed / already converted.
            if (!userPreferenceService.canDeliver(
                    userId, campaign.getId(), campaign.getChannel())) {
                return;
            }

            String dedupKey = campaignExecutionId != null
                    ? DedupKeyUtil.forScheduled(campaignExecutionId, userId)
                    : DedupKeyUtil.forEvent(campaign.getId(), userId, "scheduled_" + userId);

            DeliveryTaskDto dto = DeliveryTaskDto.builder()
                    .campaignId(campaign.getId())
                    .userId(userId)
                    .dedupKey(dedupKey)
                    .channel(campaign.getChannel())
                    .messageContent(campaign.getMessageTemplate())
                    .createdAt(LocalDateTime.now())
                    .build();

            deliveryService.createDeliveryTask(dto);
        } catch (Exception e) {
            if (isBusinessException(e)) {
                log.info("Batch evaluation skipped for campaign {} user {}: {}",
                        campaign.getId(), userId, e.getMessage());
                return;
            }
            log.error("Batch evaluation infrastructure failure for campaign {} user {}: {}",
                    campaign.getId(), userId, e.getMessage(), e);
            throw e;
        }
    }

    // ---------- rule evaluation ----------

    private List<CampaignRule> loadRules(Long campaignId) {
        return campaignRuleMapper.selectList(
                new LambdaQueryWrapper<CampaignRule>()
                        .eq(CampaignRule::getCampaignId, campaignId)
                        .eq(CampaignRule::getEnabled, 1)
                        .orderByAsc(CampaignRule::getPriority));
    }

    private boolean allRulesMatched(List<CampaignRule> rules, Long userId, Map<String, Object> eventMap) {
        for (CampaignRule rule : rules) {
            if (!evaluateRule(rule, userId, eventMap)) {
                return false;
            }
        }
        return true;
    }

    private boolean inActiveWindow(Campaign campaign) {
        LocalDateTime now = LocalDateTime.now();
        if (campaign.getStartTime() != null && now.isBefore(campaign.getStartTime())) return false;
        if (campaign.getEndTime() != null && now.isAfter(campaign.getEndTime())) return false;
        return true;
    }

    private boolean evaluateRule(CampaignRule rule, Long userId, Map<String, Object> eventMap) {
        try {
            Map<String, Object> ruleConfig = JsonUtil.fromJson(rule.getRuleConfig(), Map.class);
            String ruleType = rule.getRuleType();

            if ("PROFILE".equals(ruleType)) {
                String tagName = (String) ruleConfig.get("tagName");
                String operator = (String) ruleConfig.get("operator");
                String value = String.valueOf(ruleConfig.get("value"));

                if (tagName != null) {
                    boolean hasTag = profileService.hasTag(userId, tagName);
                    if ("EQ".equals(operator)) {
                        return hasTag == "1".equals(value);
                    }
                    return hasTag;
                }

                String metricType = (String) ruleConfig.get("metricType");
                if (metricType != null) {
                    Long metricValue = profileService.getMetricValue(userId, metricType);
                    Long threshold = Long.valueOf(String.valueOf(ruleConfig.get("threshold")));
                    return compare(metricValue, operator, threshold);
                }
            } else if ("EVENT".equals(ruleType)) {
                String propKey = (String) ruleConfig.get("propertyKey");
                String propValue = String.valueOf(ruleConfig.get("propertyValue"));
                if (propKey != null) {
                    Map<String, Object> props = getProperties(eventMap);
                    Object actual = props.get(propKey);
                    return propValue.equals(String.valueOf(actual));
                }
            } else if ("FREQUENCY".equals(ruleType)) {
                String metricType = (String) ruleConfig.get("metricType");
                String operator = (String) ruleConfig.get("operator");
                Long threshold = Long.valueOf(String.valueOf(ruleConfig.get("threshold")));
                Long metricValue = profileService.getMetricValue(userId, metricType);
                return compare(metricValue, operator, threshold);
            }

            return true; // unknown rule type → pass
        } catch (Exception e) {
            log.error("Rule evaluation error: {}", e.getMessage());
            return false;
        }
    }

    private boolean compare(Long left, String operator, Long right) {
        if (left == null) left = 0L;
        if (right == null) right = 0L;
        return switch (operator) {
            case "GT" -> left > right;
            case "LT" -> left < right;
            case "GTE" -> left >= right;
            case "LTE" -> left <= right;
            case "EQ" -> left.equals(right);
            case "NE" -> !left.equals(right);
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getProperties(Map<String, Object> eventMap) {
        Object props = eventMap.get("properties");
        if (props instanceof Map) return (Map<String, Object>) props;
        return Collections.emptyMap();
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(String.valueOf(val));
    }
}
