package com.pulseflow.campaign.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.campaign.delay.DelayedTaskManager;
import com.pulseflow.campaign.delivery.DeliveryService;
import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.common.util.CartEvent;
import com.pulseflow.common.util.DedupKeyUtil;
import com.pulseflow.common.util.DelayedTaskConstants;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignExecution;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.profile.service.ProfileService;
import com.pulseflow.profile.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
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
    private final UserEventMapper userEventMapper;

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
                if (allRulesMatched(rules, userId, eventMap, null)) {
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

        if (!"ADD_CART".equals(eventType)) return;

        // ---- DELAYED campaigns (cart abandon) ----
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

                String cartItemId = CartEvent.itemId(getProperties(eventMap));
                if (cartItemId == null) {
                    log.warn("DELAYED campaign {} matched event {} but no cartItemId in properties, skip",
                            campaign.getId(), eventId);
                    continue;
                }

                int delaySeconds = campaign.getDelaySeconds() != null ? campaign.getDelaySeconds() : 0;
                if (delaySeconds <= 0) {
                    log.warn("DELAYED campaign {} has non-positive delay_seconds, skip", campaign.getId());
                    continue;
                }

                String taskId = DedupKeyUtil.forDelayed(
                        campaign.getId(), userId, cartItemId, eventId);
                String time = (String) eventMap.get("effectiveEventTime");
                LocalDateTime addedAt = time == null ? LocalDateTime.now()
                        : LocalDateTime.parse(time.replace(' ', 'T'));
                long executeAtMillis = addedAt.plusSeconds(delaySeconds)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

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

    /** Select a page outside a transaction, then fence its short write against the current lease. */
    public boolean evaluateBatch(Campaign campaign, List<Long> userIds,
                                 CampaignExecution attempt, BehaviorWindow window) {
        LocalDateTime now = LocalDateTime.now();
        if (!window.today().equals(now.toLocalDate())) {
            throw new IllegalStateException("Calendar date changed while selecting the audience");
        }
        List<Long> candidates = inActiveWindow(campaign)
                ? matchWindowAudience(userIds, loadRules(campaign.getId()), now) : List.of();
        List<Long> eligible = userPreferenceService.deliverableUsers(candidates, campaign.getId(), campaign.getChannel());
        return deliveryService.createScheduledPage(campaign, attempt, eligible, window);
    }

    /** Audience preview and execution share the window predicate and payment fact lookup. */
    public List<Long> matchScheduledAudience(List<Long> userIds, List<CampaignRule> rules, LocalDateTime now) {
        return withoutRecentPayments(matchWindowAudience(userIds, rules, now), new BehaviorWindow(now.toLocalDate()));
    }

    private List<Long> matchWindowAudience(List<Long> userIds, List<CampaignRule> rules, LocalDateTime now) {
        Map<Long, Map<String, BigDecimal>> snapshots = profileService.readCurrentWindowMetrics(userIds, now);
        return userIds.stream().filter(userId -> {
            Map<String, BigDecimal> metrics = snapshots.getOrDefault(userId, Map.of());
            return metrics.getOrDefault(BehaviorWindow.ACTIVE_DAYS, BigDecimal.ZERO)
                            .compareTo(BigDecimal.valueOf(2)) >= 0
                    && metrics.getOrDefault(BehaviorWindow.ORDER_COUNT, BigDecimal.ZERO).signum() == 0
                    && allRulesMatched(rules, userId, Map.of(), metrics);
        }).toList();
    }

    private List<Long> withoutRecentPayments(List<Long> userIds, BehaviorWindow window) {
        if (userIds.isEmpty()) return userIds;
        var paid = new HashSet<>(userEventMapper.selectPaidUsers(
                userIds, window.purchaseStart(), window.endExclusive()));
        return userIds.stream().filter(id -> !paid.contains(id)).toList();
    }

    // ---------- rule evaluation ----------

    /**
     * 读取指定 Campaign 已启用的规则，并按优先级升序返回。
     */
    private List<CampaignRule> loadRules(Long campaignId) {
        return campaignRuleMapper.selectList(
                new LambdaQueryWrapper<CampaignRule>()
                        .eq(CampaignRule::getCampaignId, campaignId)
                        .eq(CampaignRule::getEnabled, 1)
                        .orderByAsc(CampaignRule::getPriority));
    }

    /**
     * 逐条执行规则并在首个失败处短路；空规则列表按全部匹配处理。
     */
    private boolean allRulesMatched(List<CampaignRule> rules, Long userId, Map<String, Object> eventMap,
                                    Map<String, BigDecimal> windowMetrics) {
        for (CampaignRule rule : rules) {
            if (!evaluateRule(rule, userId, eventMap, windowMetrics)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查当前本地时间是否位于 Campaign 起止窗口内；未配置的边界不限制，恰好等于边界时允许。
     */
    private boolean inActiveWindow(Campaign campaign) {
        LocalDateTime now = LocalDateTime.now();
        if (campaign.getStartTime() != null && now.isBefore(campaign.getStartTime())) return false;
        if (campaign.getEndTime() != null && now.isAfter(campaign.getEndTime())) return false;
        return true;
    }

    /** Treat malformed stored rules as non-matches and let storage failures reach recovery. */
    private boolean evaluateRule(CampaignRule rule, Long userId, Map<String, Object> eventMap,
                                 Map<String, BigDecimal> windowMetrics) {
        Map<String, Object> config;
        try {
            config = JsonUtil.fromJson(rule.getRuleConfig(), Map.class);
        } catch (RuntimeException e) {
            log.warn("Cannot parse rule {} ({})", rule.getId(), e.getClass().getSimpleName());
            return false;
        }
        if (config == null) return false;
        String ruleType = rule.getRuleType();
        String operator = String.valueOf(config.get("operator"));
        if ("PROFILE".equals(ruleType) && config.get("tagName") != null) {
            if (!(config.get("tagName") instanceof String tagName) || tagName.isBlank()) return false;
            String value = String.valueOf(config.get("value"));
            if ("EQ".equals(operator) && !"1".equals(value) && !"0".equals(value)) return false;
            boolean hasTag = profileService.hasTag(userId, tagName);
            return "EQ".equals(operator) ? hasTag == "1".equals(value) : hasTag;
        }
        if (("PROFILE".equals(ruleType) || "FREQUENCY".equals(ruleType))
                && config.get("metricType") instanceof String metricType) {
            BigDecimal threshold;
            try {
                threshold = new BigDecimal(String.valueOf(config.get("threshold")));
            } catch (NumberFormatException e) {
                log.warn("Invalid metric threshold for rule {}", rule.getId());
                return false;
            }
            String type = ProfileService.ruleMetricType(metricType);
            BigDecimal value = windowMetrics != null && ProfileService.isCurrentMetric(type)
                    ? windowMetrics.getOrDefault(type, BigDecimal.ZERO)
                    : profileService.getRuleMetric(userId, type);
            return compare(value, operator, threshold);
        }
        if ("EVENT".equals(ruleType)) {
            return config.get("propertyKey") instanceof String propKey && !propKey.isBlank()
                    && config.containsKey("propertyValue")
                    && String.valueOf(config.get("propertyValue"))
                    .equals(String.valueOf(getProperties(eventMap).get(propKey)));
        }
        // Keep the extension behavior for unknown legacy rule types, but do not broaden known rules.
        return !"PROFILE".equals(ruleType) && !"FREQUENCY".equals(ruleType);
    }

    private boolean compare(BigDecimal left, String operator, BigDecimal right) {
        int result = (left == null ? BigDecimal.ZERO : left).compareTo(right);
        return switch (operator) {
            case "GT" -> result > 0;
            case "LT" -> result < 0;
            case "GTE" -> result >= 0;
            case "LTE" -> result <= 0;
            case "EQ" -> result == 0;
            case "NE" -> result != 0;
            default -> false;
        };
    }

    /**
     * 从事件上下文提取 properties 映射；缺失或类型不符时返回空映射。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getProperties(Map<String, Object> eventMap) {
        Object props = eventMap.get("properties");
        if (props instanceof Map) return (Map<String, Object>) props;
        return Collections.emptyMap();
    }

    /**
     * 将数值或整数字符串转换为 Long，null 返回零；非法文本继续抛出解析异常。
     */
    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(String.valueOf(val));
    }
}
