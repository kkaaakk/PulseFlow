package com.pulseflow.boot.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向控制台页面的业务视图集合，隔离页面契约与数据库实体。
 *
 * Small, page-oriented DTOs used by the local web console.
 *
 * <p>The console consumes business views instead of exposing MyBatis entities
 * or table-shaped CRUD responses. Keeping the DTOs together makes the v1 API
 * contract easy to discover while the query layer is still intentionally
 * small.</p>
 */
public final class WebDtos {

    /** 隐藏工具类构造入口，避免创建没有状态的工具类实例。 */
    private WebDtos() {
    }

    /** 统一分页结果，包含条目、页码、每页数量、总数和总页数。 */
    public record PageResponse<T>(
            List<T> items,
            int page,
            int pageSize,
            long total,
            int totalPages
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public PageResponse {
            items = items == null ? List.of() : List.copyOf(items);
            page = Math.max(page, 1);
            pageSize = Math.max(pageSize, 1);
            total = Math.max(total, 0);
            totalPages = Math.max(totalPages, total == 0 ? 0 : (int) Math.ceil((double) total / pageSize));
        }
    }

    /** 仪表盘当天业务指标和比率摘要。 */
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

    /** 趋势图中的一个时间标签及对应数值。 */
    public record TrendPoint(String label, long value) {
    }

    /** 事件、触达及转化的趋势序列集合。 */
    public record DashboardTrends(
            List<TrendPoint> events,
            List<TrendPoint> deliveries,
            List<TrendPoint> conversions
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public DashboardTrends {
            events = events == null ? List.of() : List.copyOf(events);
            deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
            conversions = conversions == null ? List.of() : List.copyOf(conversions);
        }
    }

    /** 活动列表中的配置与绩效摘要。 */
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

    /** 控制台活动基础配置视图。 */
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

    /** 活动规则的展示视图，包含解析后的配置。 */
    public record RuleView(
            Long id,
            String name,
            String type,
            Object config,
            Integer priority,
            boolean enabled
    ) {
    }

    /** 活动人群预估视图，保留人数与版本口径。 */
    public record AudienceView(
            long estimatedCount,
            String dataVersion,
            String calculationMode,
            List<String> warnings
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public AudienceView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 活动触达计数及状态摘要。 */
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

    /** 活动归因计数与相关业务摘要。 */
    public record AttributionSummary(
            long attributedConversions,
            String model,
            Integer windowHours
    ) {
    }

    /** 活动详情组合，包含基础配置、规则、人群及触达归因摘要。 */
    public record CampaignDetail(
            CampaignView campaign,
            List<RuleView> rules,
            AudienceView audience,
            DeliverySummary deliverySummary,
            AttributionSummary attributionSummary
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public CampaignDetail {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** 活动绩效展示数据，统一发送、点击和转化口径。 */
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

    /** 用户列表中的档案、活跃和消费摘要及标签。 */
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
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public UserListItem {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** 面向页面的用户基础档案。 */
    public record UserProfileView(
            Long userId,
            String nickname,
            String avatar,
            int status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /** 用户全景组合，包含基础资料、实时和窗口画像、标签及事件。 */
    public record UserDetail(
            UserProfileView profile,
            Map<String, String> realtimeMetrics,
            String realtimeSource,
            boolean realtimeAvailable,
            Map<String, Object> windowMetrics,
            List<String> tags,
            PageResponse<EventView> recentEvents
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public UserDetail {
            realtimeMetrics = realtimeMetrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(realtimeMetrics));
            windowMetrics = windowMetrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(windowMetrics));
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** 事件事实展示视图，区分原始时间和有效时间。 */
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
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public EventView {
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    /** 触达任务列表视图，包含渠道、用户及发送状态。 */
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

    /** 单次渠道发送记录的展示视图。 */
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

    /** 触达点击事实及扩展属性视图。 */
    public record ClickView(
            Long id,
            Long taskId,
            Long userId,
            String clickSource,
            LocalDateTime clickTime,
            Map<String, Object> properties
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public ClickView {
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    /** 目标事件到活动触达的归因展示视图。 */
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

    /** 触达任务及发送、点击和归因记录的组合详情。 */
    public record DeliveryDetail(
            DeliveryListItem task,
            DeliveryRecordView record,
            List<ClickView> clicks,
            List<AttributionView> attributions
    ) {
        /** 构造响应快照；复制集合或补齐默认值，防止调用方修改内部数据。 */
        public DeliveryDetail {
            clicks = clicks == null ? List.of() : List.copyOf(clicks);
            attributions = attributions == null ? List.of() : List.copyOf(attributions);
        }
    }

    /** 后端与各基础设施的健康状态。 */
    public record SystemStatus(
            String backend,
            String mysql,
            String redis,
            String kafka
    ) {
    }

    /** 前端恢复认证所需的操作员展示信息和令牌元数据。 */
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
