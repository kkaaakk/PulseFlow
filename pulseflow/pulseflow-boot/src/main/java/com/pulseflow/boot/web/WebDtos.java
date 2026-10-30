package com.pulseflow.boot.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, page-oriented DTOs used by the local web console.
 *
 * <p>The console consumes business views instead of exposing MyBatis entities
 * or table-shaped CRUD responses. Keeping the DTOs together makes the v1 API
 * contract easy to discover while the query layer is still intentionally
 * small.</p>
 */
public final class WebDtos {

    /**
     * 禁止实例化仅用于组织控制台响应类型的容器类。
     */
    private WebDtos() {
    }

    public record PageResponse<T>(
            List<T> items,
            int page,
            int pageSize,
            long total,
            int totalPages
    ) {
        /**
         * 构造分页响应并防御性复制 items；空列表统一为不可变空列表。
         */
        public PageResponse {
            items = items == null ? List.of() : List.copyOf(items);
            page = Math.max(page, 1);
            pageSize = Math.max(pageSize, 1);
            total = Math.max(total, 0);
            totalPages = Math.max(totalPages, total == 0 ? 0 : (int) Math.ceil((double) total / pageSize));
        }
    }

    public record DashboardSummary(
            long todayEvents,
            long activeUsers,
            long runningCampaigns,
            long todayDeliveries,
            BigDecimal deliverySuccessRate,
            long todayClicks,
            long todayConversions,
            long todayAttributions
    ) {
    }

    public record TrendPoint(String label, long value) {
    }

    public record DashboardTrends(
            List<TrendPoint> events,
            List<TrendPoint> deliveries,
            List<TrendPoint> conversions
    ) {
        /**
         * 构造总览趋势响应并防御性复制各趋势序列，空序列统一为空列表。
         */
        public DashboardTrends {
            events = events == null ? List.of() : List.copyOf(events);
            deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
            conversions = conversions == null ? List.of() : List.copyOf(conversions);
        }
    }

    public record CampaignListItem(
            Long id,
            String name,
            String status,
            String triggerType,
            String channel,
            long audience,
            long sent,
            long clicked,
            long converted,
            LocalDateTime createdAt,
            Long createdBy
    ) {
    }

    public record CampaignView(
            Long id,
            String name,
            String description,
            String status,
            String triggerType,
            String channel,
            String eventTypes,
            String cronExpression,
            Integer delaySeconds,
            Integer userDailyLimit,
            Integer campaignWeeklyLimit,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime nextTriggerAt,
            LocalDateTime lastTriggerAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long createdBy
    ) {
    }

    public record RuleView(
            Long id,
            String name,
            String type,
            Object config,
            Integer priority,
            boolean enabled
    ) {
    }

    public record AudienceView(
            long estimatedCount,
            String dataVersion,
            String calculationMode,
            List<String> warnings
    ) {
        /**
         * 构造人群预估视图并复制警告列表，避免调用方随后修改响应内容。
         */
        public AudienceView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record DeliverySummary(
            long sent,
            long delivered,
            long clicked,
            long converted,
            BigDecimal deliveryRate,
            BigDecimal clickRate,
            BigDecimal conversionRate
    ) {
    }

    public record AttributionSummary(
            long attributedConversions,
            String model,
            Integer windowHours
    ) {
    }

    public record CampaignDetail(
            CampaignView campaign,
            List<RuleView> rules,
            AudienceView audience,
            DeliverySummary deliverySummary,
            AttributionSummary attributionSummary
    ) {
        /**
         * 组合 Campaign、规则、人群和效果摘要，并对规则列表作防御性复制。
         */
        public CampaignDetail {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record PerformanceView(
            Long campaignId,
            long targetAudienceCount,
            long sentCount,
            long deliveredCount,
            long clickedCount,
            long convertedCount,
            long unsubscribeCount,
            BigDecimal deliveryRate,
            BigDecimal clickRate,
            BigDecimal conversionRate,
            BigDecimal unsubscribeRate,
            LocalDateTime calculatedAt
    ) {
    }

    public record UserListItem(
            Long userId,
            String nickname,
            String avatar,
            int status,
            LocalDateTime lastActiveAt,
            BigDecimal activeDays7d,
            BigDecimal spend30d,
            List<String> tags
    ) {
        /**
         * 构造用户列表项，复制标签列表并将缺失标签统一为空列表。
         */
        public UserListItem {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record UserProfileView(
            Long userId,
            String nickname,
            String avatar,
            int status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record UserDetail(
            UserProfileView profile,
            Map<String, String> realtimeMetrics,
            String realtimeSource,
            boolean realtimeAvailable,
            Map<String, Object> windowMetrics,
            List<String> tags,
            PageResponse<EventView> recentEvents
    ) {
        /**
         * 组合基础画像、实时与窗口指标、标签及事件分页，对集合字段作防御性复制。
         */
        public UserDetail {
            realtimeMetrics = realtimeMetrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(realtimeMetrics));
            windowMetrics = windowMetrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(windowMetrics));
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record EventView(
            Long id,
            String eventId,
            Long userId,
            String eventType,
            Long targetId,
            LocalDateTime eventTime,
            LocalDateTime receivedAt,
            LocalDateTime effectiveEventTime,
            boolean clockSkew,
            Map<String, Object> properties
    ) {
        /**
         * 构造事件视图并复制 properties，缺失事件属性统一为空映射。
         */
        public EventView {
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    public record DeliveryListItem(
            Long taskId,
            Long campaignId,
            String campaignName,
            Long userId,
            String channel,
            String status,
            String dispatchStatus,
            Integer retryCount,
            String triggerEventId,
            LocalDateTime createdAt,
            LocalDateTime sentAt
    ) {
    }

    public record DeliveryRecordView(
            Long id,
            Long taskId,
            Long campaignId,
            Long userId,
            String channel,
            String status,
            LocalDateTime sentAt,
            String errorMessage
    ) {
    }

    public record ClickView(
            Long id,
            Long taskId,
            Long userId,
            String clickSource,
            LocalDateTime clickTime,
            Map<String, Object> properties
    ) {
        /**
         * 构造点击视图并复制 properties，避免外部修改原映射影响响应。
         */
        public ClickView {
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    public record AttributionView(
            Long id,
            Long userId,
            Long campaignId,
            String campaignName,
            Long clickEventId,
            String targetEventId,
            Long taskId,
            String attributionModel,
            Integer attributionWindowHours,
            LocalDateTime creditedAt
    ) {
    }

    public record DeliveryDetail(
            DeliveryListItem task,
            DeliveryRecordView record,
            List<ClickView> clicks,
            List<AttributionView> attributions
    ) {
        /**
         * 组合触达任务、发送记录、点击与归因明细，并复制明细列表。
         */
        public DeliveryDetail {
            clicks = clicks == null ? List.of() : List.copyOf(clicks);
            attributions = attributions == null ? List.of() : List.copyOf(attributions);
        }
    }

    public record SystemStatus(
            String backend,
            String mysql,
            String redis,
            String kafka
    ) {
    }

    public record AuthSession(
            Long operatorId,
            String role,
            String displayName,
            String tokenName,
            String tokenValue,
            String loginId
    ) {
    }
}
