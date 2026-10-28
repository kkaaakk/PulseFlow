package com.pulseflow.campaign.decision;

import com.pulseflow.campaign.delay.DelayedTaskManager;
import com.pulseflow.campaign.delivery.DeliveryService;
import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import com.pulseflow.profile.service.ProfileService;
import com.pulseflow.profile.service.UserPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 验证 DecisionEngine 的业务行为和边界条件。
 *
 * Unit tests for {@link DecisionEngine} — the core rule-matching and delivery
 * dispatch logic. All external dependencies (mappers, Redis, profile service)
 * are mocked so these run without Docker/MySQL/Redis.
 *
 * <p>Covers the 6 highest-value decision paths identified in the stage 7.2 plan:
 * profile-tag match, profile-metric threshold, event-property match, frequency
 * cap block, dedup-key idempotency, and delayed-task scheduling.</p>
 */
class DecisionEngineTest {

    private CampaignMapper campaignMapper;
    private CampaignRuleMapper campaignRuleMapper;
    private ProfileService profileService;
    private DeliveryService deliveryService;
    private DelayedTaskManager delayedTaskManager;
    private UserPreferenceService userPreferenceService;
    private DecisionEngine decisionEngine;

    /** 初始化当前测试的实体、依赖替身和数据，使各场景之间保持隔离。 */
    @BeforeEach
    void setUp() {
        campaignMapper = mock(CampaignMapper.class);
        campaignRuleMapper = mock(CampaignRuleMapper.class);
        profileService = mock(ProfileService.class);
        deliveryService = mock(DeliveryService.class);
        delayedTaskManager = mock(DelayedTaskManager.class);
        userPreferenceService = mock(UserPreferenceService.class);
        decisionEngine = new DecisionEngine(
                campaignMapper, campaignRuleMapper, profileService,
                deliveryService, delayedTaskManager, userPreferenceService);

        // Default: user passes pre-filter (DND / unsub / converted).
        when(userPreferenceService.canDeliver(anyLong(), anyLong(), anyString())).thenReturn(true);
    }

    // ---------- helpers ----------

    /**
     * 构造即时触发活动夹具，供决策测试选择命中场景。
     * Build an ACTIVE EVENT-trigger campaign active right now. */
    private Campaign eventCampaign(Long id, String eventTypes) {
        return Campaign.builder()
                .id(id)
                .name("event-campaign-" + id)
                .status(CampaignStatus.ACTIVE.name())
                .triggerType(TriggerType.EVENT.name())
                .eventTypes(eventTypes)
                .channel("IN_APP")
                .messageTemplate("hello-user")
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(1))
                .build();
    }

    /**
     * 构造带延迟配置的活动，供购物车延迟任务测试使用。
     * Build an ACTIVE DELAYED-trigger campaign (e.g. cart-abandon recall). */
    private Campaign delayedCampaign(Long id, String eventTypes, int delaySeconds) {
        return Campaign.builder()
                .id(id)
                .name("delayed-campaign-" + id)
                .status(CampaignStatus.ACTIVE.name())
                .triggerType(TriggerType.DELAYED.name())
                .eventTypes(eventTypes)
                .channel("IN_APP")
                .messageTemplate("cart-recall")
                .delaySeconds(delaySeconds)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(1))
                .build();
    }

    /** 按指定规则类型和 JSON 配置构造规则夹具。 */
    private CampaignRule rule(Long campaignId, String type, String configJson) {
        return CampaignRule.builder()
                .id(1L)
                .campaignId(campaignId)
                .ruleName("rule-" + type)
                .ruleType(type)
                .ruleConfig(configJson)
                .priority(0)
                .enabled(1)
                .build();
    }

    /** 创建含用户、事件类型和属性的决策上下文。 */
    private Map<String, Object> eventMap(String eventId, Long userId, String eventType,
                                          Map<String, Object> properties) {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("userId", userId);
        map.put("eventType", eventType);
        map.put("properties", properties);
        return map;
    }

    // ---------- tests ----------

    /** 验证画像标签条件命中后创建触达任务。 */
    @Test
    @DisplayName("PROFILE tag EQ rule matches → creates DeliveryTask")
    void profileTagRuleMatches() {
        Campaign campaign = eventCampaign(100L, "PAGE_VIEW");
        when(campaignMapper.selectList(any())).thenReturn(List.of(campaign), List.of());
        when(campaignRuleMapper.selectList(any())).thenReturn(List.of(
                rule(100L, "PROFILE", "{\"tagName\":\"HIGH_VALUE\",\"operator\":\"EQ\",\"value\":\"1\"}")));
        when(profileService.hasTag(1001L, "HIGH_VALUE")).thenReturn(true);

        decisionEngine.evaluate(eventMap("evt-1", 1001L, "PAGE_VIEW", Map.of()));

        verify(deliveryService).createDeliveryTask(any(DeliveryTaskDto.class));
    }

    /** 验证画像指标大于等于阈值时规则命中。 */
    @Test
    @DisplayName("PROFILE metric GTE threshold rule matches → creates DeliveryTask")
    void profileMetricRuleGteThreshold() {
        Campaign campaign = eventCampaign(101L, "PAGE_VIEW");
        when(campaignMapper.selectList(any())).thenReturn(List.of(campaign), List.of());
        when(campaignRuleMapper.selectList(any())).thenReturn(List.of(
                rule(101L, "PROFILE",
                        "{\"metricType\":\"activeDays7d\",\"operator\":\"GTE\",\"threshold\":5}")));
        when(profileService.getMetricValue(1001L, "activeDays7d")).thenReturn(10L);

        decisionEngine.evaluate(eventMap("evt-2", 1001L, "PAGE_VIEW", Map.of()));

        verify(deliveryService).createDeliveryTask(any(DeliveryTaskDto.class));
    }

    /** 验证事件属性满足条件时触发活动。 */
    @Test
    @DisplayName("EVENT property equals rule matches → creates DeliveryTask")
    void eventPropertyRuleMatches() {
        Campaign campaign = eventCampaign(102L, "ADD_TO_CART");
        when(campaignMapper.selectList(any())).thenReturn(List.of(campaign), List.of());
        when(campaignRuleMapper.selectList(any())).thenReturn(List.of(
                rule(102L, "EVENT",
                        "{\"propertyKey\":\"category\",\"propertyValue\":\"electronics\"}")));

        decisionEngine.evaluate(eventMap("evt-3", 1001L, "ADD_TO_CART",
                Map.of("category", "electronics")));

        verify(deliveryService).createDeliveryTask(any(DeliveryTaskDto.class));
    }

    /** 验证频控规则超限时不生成触达任务。 */
    @Test
    @DisplayName("FREQUENCY rule over limit → rule does NOT match → no DeliveryTask")
    void frequencyRuleBlocksOverLimit() {
        Campaign campaign = eventCampaign(103L, "PAGE_VIEW");
        when(campaignMapper.selectList(any())).thenReturn(List.of(campaign), List.of());
        // FREQUENCY rule: sendCount24h LT 3 (user has 5 → 5 < 3 is false → rule fails)
        when(campaignRuleMapper.selectList(any())).thenReturn(List.of(
                rule(103L, "FREQUENCY",
                        "{\"metricType\":\"sendCount24h\",\"operator\":\"LT\",\"threshold\":3}")));
        when(profileService.getMetricValue(1001L, "sendCount24h")).thenReturn(5L);

        decisionEngine.evaluate(eventMap("evt-4", 1001L, "PAGE_VIEW", Map.of()));

        verify(deliveryService, never()).createDeliveryTask(any());
    }

    /** 验证重复幂等键被当作业务跳过，不传播为基础设施故障。 */
    @Test
    @DisplayName("DuplicateKeyException from delivery is a business skip — no re-throw")
    void dedupDuplicateKeySkipsSilently() {
        Campaign campaign = eventCampaign(104L, "PAGE_VIEW");
        when(campaignMapper.selectList(any())).thenReturn(List.of(campaign), List.of());
        when(campaignRuleMapper.selectList(any())).thenReturn(List.of(
                rule(104L, "PROFILE", "{\"tagName\":\"HIGH_VALUE\",\"operator\":\"EQ\",\"value\":\"1\"}")));
        when(profileService.hasTag(1001L, "HIGH_VALUE")).thenReturn(true);
        doThrow(new DuplicateKeyException("Duplicate dedup key"))
                .when(deliveryService).createDeliveryTask(any());

        // Must not throw — dedup hit is a business-level skip, not an infra failure.
        assertThatCode(() -> decisionEngine.evaluate(eventMap("evt-5", 1001L, "PAGE_VIEW", Map.of())))
                .doesNotThrowAnyException();

        verify(deliveryService).createDeliveryTask(any(DeliveryTaskDto.class));
    }

    /** 验证延迟任务包含购物车项标识并按配置排程。 */
    @Test
    @DisplayName("DELAYED campaign with cartItemId → schedules delayed task")
    void delayedTaskScheduledWithCartItemId() {
        Campaign campaign = delayedCampaign(200L, "ADD_TO_CART", 1800);
        // First selectList (EVENT) returns empty; second (DELAYED) returns our campaign.
        when(campaignMapper.selectList(any())).thenReturn(List.of(), List.of(campaign));

        decisionEngine.evaluate(eventMap("evt-6", 1001L, "ADD_TO_CART",
                Map.of("cartItemId", "SKU-123")));

        // taskId = campaignId:userId:cartItemId:eventId = "200:1001:SKU-123:evt-6"
        verify(delayedTaskManager).addDelayedTask(
                eq("DELAYED_CAMPAIGN"),
                eq("200:1001:SKU-123:evt-6"),
                anyLong());
        verify(deliveryService, never()).createDeliveryTask(any());
    }
}
