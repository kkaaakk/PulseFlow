package com.pulseflow.boot.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.support.AiForbiddenException;
import com.pulseflow.ai.support.AiResourceNotFoundException;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.AttributionRecord;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.entity.ClickEvent;
import com.pulseflow.entity.DeliveryRecord;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.entity.UserBehaviorSummary;
import com.pulseflow.entity.UserEvent;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.entity.UserTag;
import com.pulseflow.mapper.AttributionRecordMapper;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import com.pulseflow.mapper.ClickEventMapper;
import com.pulseflow.mapper.DeliveryRecordMapper;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.pulseflow.mapper.UserTagMapper;
import com.pulseflow.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Aggregates business-facing read models for the web console.
 *
 * <p>Write paths remain in their existing domain services. This service only
 * composes existing mappers and the authoritative performance tables, so the
 * browser never receives a mapper-shaped CRUD contract.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebQueryService {

    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private final CampaignMapper campaignMapper;
    private final CampaignRuleMapper campaignRuleMapper;
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryRecordMapper deliveryRecordMapper;
    private final ClickEventMapper clickEventMapper;
    private final AttributionRecordMapper attributionRecordMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserEventMapper userEventMapper;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;
    private final UserTagMapper userTagMapper;
    private final ProfileService profileService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<AiFeatureProperties> aiProperties;
    private final RedissonClient redissonClient;

    public WebDtos.DashboardSummary dashboardSummary() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        long todayEvents = count("SELECT COUNT(*) FROM user_event WHERE effective_event_time >= ?", start);
        long activeUsers = count("SELECT COUNT(DISTINCT user_id) FROM user_event WHERE effective_event_time >= ?", start);
        long runningCampaigns = campaignMapper.selectCount(
                new LambdaQueryWrapper<Campaign>().eq(Campaign::getStatus, "ACTIVE"));
        long todayDeliveries = count(
                "SELECT COUNT(*) FROM delivery_record WHERE status = 'SENT' AND sent_at >= ?", start);
        long delivered = count(
                "SELECT COUNT(*) FROM delivery_record WHERE status = 'SENT' AND sent_at >= ?", start);
        long todayClicks = count("SELECT COUNT(*) FROM click_event WHERE click_time >= ?", start);
        long todayConversions = count("SELECT COUNT(*) FROM attribution_record WHERE credited_at >= ?", start);
        long todayAttributions = todayConversions;

        return new WebDtos.DashboardSummary(
                todayEvents,
                activeUsers,
                runningCampaigns,
                todayDeliveries,
                ratio(delivered, todayDeliveries),
                todayClicks,
                todayConversions,
                todayAttributions);
    }

    public WebDtos.DashboardTrends dashboardTrends(int days) {
        int safeDays = Math.min(Math.max(days, 1), 30);
        LocalDateTime hourStart = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).minusHours(23);
        LocalDate dayStart = LocalDate.now().minusDays(safeDays - 1L);

        List<WebDtos.TrendPoint> events = hourlyTrend(
                "SELECT DATE_FORMAT(effective_event_time, '%Y-%m-%d %H:00:00') AS bucket, COUNT(*) AS value "
                        + "FROM user_event WHERE effective_event_time >= ? GROUP BY bucket ORDER BY bucket",
                hourStart,
                24);
        List<WebDtos.TrendPoint> deliveries = dailyTrend(
                "SELECT DATE_FORMAT(sent_at, '%Y-%m-%d') AS bucket, COUNT(*) AS value "
                        + "FROM delivery_record WHERE status = 'SENT' AND sent_at >= ? GROUP BY bucket ORDER BY bucket",
                dayStart.atStartOfDay(),
                safeDays);
        List<WebDtos.TrendPoint> conversions = dailyTrend(
                "SELECT DATE_FORMAT(credited_at, '%Y-%m-%d') AS bucket, COUNT(*) AS value "
                        + "FROM attribution_record WHERE credited_at >= ? GROUP BY bucket ORDER BY bucket",
                dayStart.atStartOfDay(),
                safeDays);
        return new WebDtos.DashboardTrends(events, deliveries, conversions);
    }

    public WebDtos.PageResponse<WebDtos.CampaignListItem> listCampaigns(
            int page, int pageSize, String keyword, String status,
            Long createdBy, String startTime, String endTime) {
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        LocalDateTime from = parseDateTime(startTime);
        LocalDateTime to = parseDateTime(endTime);
        IPage<Campaign> result = campaignMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<Campaign>()
                        .like(hasText(keyword), Campaign::getName, trim(keyword))
                        .eq(hasText(status), Campaign::getStatus, trim(status))
                        .eq(createdBy != null, Campaign::getCreatedBy, createdBy)
                        .ge(from != null, Campaign::getCreatedAt, from)
                        .le(to != null, Campaign::getCreatedAt, to)
                        .orderByDesc(Campaign::getCreatedAt));

        List<WebDtos.CampaignListItem> items = result.getRecords().stream()
                .map(campaign -> {
                    PerformanceNumbers p = performanceNumbers(campaign.getId());
                    return new WebDtos.CampaignListItem(
                            campaign.getId(), campaign.getName(), campaign.getStatus(),
                            campaign.getTriggerType(), campaign.getChannel(), p.audience(),
                            p.sent(), p.clicked(), p.converted(), campaign.getCreatedAt(), campaign.getCreatedBy());
                })
                .toList();
        return page(items, result.getTotal(), safePage, safeSize);
    }

    public WebDtos.CampaignDetail campaignDetail(Long campaignId, Long operatorId) {
        Campaign campaign = findCampaign(campaignId);
        List<WebDtos.RuleView> rules = campaignRuleMapper.selectList(
                        new LambdaQueryWrapper<CampaignRule>()
                                .eq(CampaignRule::getCampaignId, campaignId)
                                .orderByAsc(CampaignRule::getPriority, CampaignRule::getId))
                .stream()
                .map(this::toRuleView)
                .toList();
        PerformanceNumbers p = performanceNumbers(campaignId);
        WebDtos.AudienceView audience = new WebDtos.AudienceView(
                p.audience(), null, "AUTHORITATIVE", List.of());
        WebDtos.DeliverySummary deliverySummary = p.toDeliverySummary();
        WebDtos.AttributionSummary attributionSummary = new WebDtos.AttributionSummary(
                p.converted(), "CLICK_LAST_TOUCH", 24);
        WebDtos.ReviewView review = canReadReview(campaign, operatorId)
                ? reviewForCampaign(campaignId)
                : null;
        return new WebDtos.CampaignDetail(
                toCampaignView(campaign), rules, audience, deliverySummary, attributionSummary, review);
    }

    public WebDtos.PerformanceView campaignPerformance(Long campaignId) {
        findCampaign(campaignId);
        return performanceNumbers(campaignId).toView(campaignId);
    }

    public List<WebDtos.TrendPoint> campaignDeliveryTrend(Long campaignId, int days) {
        findCampaign(campaignId);
        int safeDays = Math.min(Math.max(days, 1), 30);
        LocalDate start = LocalDate.now().minusDays(safeDays - 1L);
        Map<String, Long> values;
        try {
            values = jdbcTemplate.queryForList(
                    "SELECT DATE_FORMAT(sent_at, '%Y-%m-%d') AS bucket, COUNT(*) AS value "
                            + "FROM delivery_record WHERE campaign_id = ? AND status = 'SENT' "
                            + "AND sent_at >= ? GROUP BY bucket ORDER BY bucket",
                    campaignId, start.atStartOfDay()).stream().collect(Collectors.toMap(
                    row -> string(row.get("bucket")),
                    row -> number(row.get("value")),
                    Long::sum,
                    LinkedHashMap::new));
        } catch (DataAccessException e) {
            values = Collections.emptyMap();
        }
        List<WebDtos.TrendPoint> output = new ArrayList<>();
        for (int i = 0; i < safeDays; i++) {
            LocalDate bucket = start.plusDays(i);
            output.add(new WebDtos.TrendPoint(bucket.format(DAY_LABEL), values.getOrDefault(bucket.toString(), 0L)));
        }
        return output;
    }

    public WebDtos.ReviewView campaignReview(Long campaignId, Long operatorId) {
        Campaign campaign = findCampaign(campaignId);
        requireReviewOwner(campaign, operatorId);
        return reviewForCampaign(campaignId);
    }

    public WebDtos.PageResponse<WebDtos.DeliveryListItem> campaignDeliveries(
            Long campaignId, int page, int pageSize, String userId, String channel, String status) {
        findCampaign(campaignId);
        return listDeliveries(campaignId, page, pageSize, userId, channel, status, null, null);
    }

    public WebDtos.PageResponse<WebDtos.AttributionView> campaignAttributions(
            Long campaignId, int page, int pageSize) {
        findCampaign(campaignId);
        return listAttributions(campaignId, page, pageSize, null, null, null);
    }

    public WebDtos.PageResponse<WebDtos.UserListItem> listUsers(
            int page, int pageSize, String keyword, Integer status) {
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        IPage<UserProfile> result = userProfileMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<UserProfile>()
                        .and(hasText(keyword), wrapper -> wrapper
                                .like(UserProfile::getNickname, trim(keyword))
                                .or()
                                .like(UserProfile::getUserId, trim(keyword)))
                        .eq(status != null, UserProfile::getStatus, status)
                        .orderByDesc(UserProfile::getUpdatedAt));
        List<WebDtos.UserListItem> items = result.getRecords().stream()
                .map(this::toUserListItem)
                .toList();
        return page(items, result.getTotal(), safePage, safeSize);
    }

    public WebDtos.UserDetail userDetail(Long userId, int eventPage, int eventPageSize) {
        UserProfile profile = findUser(userId);
        RealtimeResult realtime = realtimeMetrics(userId);
        Map<String, Object> window = windowMetrics(userId);
        List<String> tags = activeTags(userId);
        WebDtos.PageResponse<WebDtos.EventView> events = listUserEvents(
                userId, eventPage, eventPageSize, null, null, null);
        return new WebDtos.UserDetail(
                new WebDtos.UserProfileView(profile.getUserId(), profile.getNickname(), profile.getAvatar(),
                        profile.getStatus() == null ? 1 : profile.getStatus(), profile.getCreatedAt(), profile.getUpdatedAt()),
                realtime.metrics(), realtime.source(), realtime.available(), window, tags, events);
    }

    public WebDtos.UserProfileView userProfile(Long userId) {
        UserProfile profile = findUser(userId);
        return new WebDtos.UserProfileView(profile.getUserId(), profile.getNickname(), profile.getAvatar(),
                profile.getStatus() == null ? 1 : profile.getStatus(), profile.getCreatedAt(), profile.getUpdatedAt());
    }

    public WebDtos.PageResponse<WebDtos.EventView> listUserEvents(
            Long userId, int page, int pageSize, String eventType, String startTime, String endTime) {
        findUser(userId);
        return listEvents(page, pageSize, eventType, userId, startTime, endTime);
    }

    public WebDtos.PageResponse<WebDtos.EventView> listEvents(
            int page, int pageSize, String eventType, Long userId, String startTime, String endTime) {
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        LocalDateTime from = parseDateTime(startTime);
        LocalDateTime to = parseDateTime(endTime);
        IPage<UserEvent> result = userEventMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<UserEvent>()
                        .eq(hasText(eventType), UserEvent::getEventType, trim(eventType))
                        .eq(userId != null, UserEvent::getUserId, userId)
                        .ge(from != null, UserEvent::getEffectiveEventTime, from)
                        .le(to != null, UserEvent::getEffectiveEventTime, to)
                        .orderByDesc(UserEvent::getEffectiveEventTime));
        return page(result.getRecords().stream().map(this::toEventView).toList(),
                result.getTotal(), safePage, safeSize);
    }

    public WebDtos.EventView event(String eventId) {
        UserEvent event = userEventMapper.selectOne(
                new LambdaQueryWrapper<UserEvent>().eq(UserEvent::getEventId, eventId));
        if (event == null) {
            throw new AiResourceNotFoundException("Event not found: " + eventId);
        }
        return toEventView(event);
    }

    public WebDtos.PageResponse<WebDtos.DeliveryListItem> listDeliveries(
            Long campaignId, int page, int pageSize, String userId, String channel, String status,
            String startTime, String endTime) {
        if (campaignId != null) {
            findCampaign(campaignId);
        }
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        LocalDateTime from = parseDateTime(startTime);
        LocalDateTime to = parseDateTime(endTime);
        Long parsedUserId = parseLong(userId);
        IPage<DeliveryTask> result = deliveryTaskMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<DeliveryTask>()
                        .eq(campaignId != null, DeliveryTask::getCampaignId, campaignId)
                        .eq(parsedUserId != null, DeliveryTask::getUserId, parsedUserId)
                        .eq(hasText(channel), DeliveryTask::getChannel, trim(channel))
                        .eq(hasText(status), DeliveryTask::getStatus, trim(status))
                        .ge(from != null, DeliveryTask::getCreatedAt, from)
                        .le(to != null, DeliveryTask::getCreatedAt, to)
                        .orderByDesc(DeliveryTask::getCreatedAt));
        List<WebDtos.DeliveryListItem> items = result.getRecords().stream()
                .map(this::toDeliveryListItem)
                .toList();
        return page(items, result.getTotal(), safePage, safeSize);
    }

    public WebDtos.DeliveryDetail delivery(Long taskId) {
        DeliveryTask task = deliveryTaskMapper.selectById(taskId);
        if (task == null) {
            throw new AiResourceNotFoundException("Delivery task not found: " + taskId);
        }
        DeliveryRecord record = deliveryRecordMapper.selectOne(
                new LambdaQueryWrapper<DeliveryRecord>().eq(DeliveryRecord::getTaskId, taskId));
        List<ClickEvent> clicks = clickEventMapper.selectList(
                new LambdaQueryWrapper<ClickEvent>().eq(ClickEvent::getTaskId, taskId)
                        .orderByDesc(ClickEvent::getClickTime));
        List<AttributionRecord> attributions = attributionRecordMapper.selectList(
                new LambdaQueryWrapper<AttributionRecord>().eq(AttributionRecord::getTaskId, taskId)
                        .orderByDesc(AttributionRecord::getCreditedAt));
        return new WebDtos.DeliveryDetail(
                toDeliveryListItem(task),
                record == null ? null : toDeliveryRecordView(record),
                clicks.stream().map(this::toClickView).toList(),
                attributions.stream().map(this::toAttributionView).toList());
    }

    public WebDtos.PageResponse<WebDtos.AttributionView> listAttributions(
            Long campaignId, int page, int pageSize, String userId, String model, String startTime) {
        if (campaignId != null) {
            findCampaign(campaignId);
        }
        int safePage = safePage(page);
        int safeSize = safePageSize(pageSize);
        Long parsedUserId = parseLong(userId);
        LocalDateTime from = parseDateTime(startTime);
        IPage<AttributionRecord> result = attributionRecordMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<AttributionRecord>()
                        .eq(campaignId != null, AttributionRecord::getCampaignId, campaignId)
                        .eq(parsedUserId != null, AttributionRecord::getUserId, parsedUserId)
                        .eq(hasText(model), AttributionRecord::getAttributionModel, trim(model))
                        .ge(from != null, AttributionRecord::getCreditedAt, from)
                        .orderByDesc(AttributionRecord::getCreditedAt));
        return page(result.getRecords().stream().map(this::toAttributionView).toList(),
                result.getTotal(), safePage, safeSize);
    }

    public WebDtos.AttributionView attribution(Long attributionId) {
        AttributionRecord record = attributionRecordMapper.selectById(attributionId);
        if (record == null) {
            throw new AiResourceNotFoundException("Attribution not found: " + attributionId);
        }
        return toAttributionView(record);
    }

    public WebDtos.SystemStatus systemStatus() {
        return new WebDtos.SystemStatus(
                "UP",
                mysqlStatus(),
                redisStatus(),
                kafkaStatus(),
                aiMode(),
                piiMode());
    }

    private WebDtos.CampaignView toCampaignView(Campaign campaign) {
        return new WebDtos.CampaignView(
                campaign.getId(), campaign.getName(), campaign.getDescription(), campaign.getStatus(),
                campaign.getTriggerType(), campaign.getChannel(), campaign.getEventTypes(),
                campaign.getCronExpression(), campaign.getDelaySeconds(), campaign.getUserDailyLimit(),
                campaign.getCampaignWeeklyLimit(), campaign.getStartTime(), campaign.getEndTime(),
                campaign.getNextTriggerAt(), campaign.getLastTriggerAt(), campaign.getCreatedAt(),
                campaign.getUpdatedAt(), campaign.getCreatedBy());
    }

    private WebDtos.RuleView toRuleView(CampaignRule rule) {
        return new WebDtos.RuleView(
                rule.getId(), rule.getRuleName(), rule.getRuleType(), parseJson(rule.getRuleConfig()),
                rule.getPriority(), Objects.equals(rule.getEnabled(), 1));
    }

    private WebDtos.UserListItem toUserListItem(UserProfile profile) {
        Map<String, Object> metrics = windowMetrics(profile.getUserId());
        UserEvent lastEvent = userEventMapper.selectOne(
                new LambdaQueryWrapper<UserEvent>().eq(UserEvent::getUserId, profile.getUserId())
                        .orderByDesc(UserEvent::getEffectiveEventTime).last("LIMIT 1"));
        return new WebDtos.UserListItem(
                profile.getUserId(), profile.getNickname(), profile.getAvatar(),
                profile.getStatus() == null ? 1 : profile.getStatus(),
                lastEvent == null ? null : lastEvent.getEffectiveEventTime(),
                decimal(metrics.get("activeDays7d")), decimal(metrics.get("spend30d")), activeTags(profile.getUserId()));
    }

    private WebDtos.EventView toEventView(UserEvent event) {
        return new WebDtos.EventView(
                event.getId(), event.getEventId(), event.getUserId(), event.getEventType(), event.getTargetId(),
                event.getEventTime(), event.getReceivedAt(), event.getEffectiveEventTime(),
                Objects.equals(event.getClockSkew(), 1), parseJson(event.getProperties()));
    }

    private WebDtos.DeliveryListItem toDeliveryListItem(DeliveryTask task) {
        Campaign campaign = campaignMapper.selectById(task.getCampaignId());
        DeliveryRecord record = deliveryRecordMapper.selectOne(
                new LambdaQueryWrapper<DeliveryRecord>().eq(DeliveryRecord::getTaskId, task.getId()));
        return new WebDtos.DeliveryListItem(
                task.getId(), task.getCampaignId(), campaign == null ? "Campaign " + task.getCampaignId() : campaign.getName(),
                task.getUserId(), task.getChannel(), task.getStatus(), task.getDispatchStatus(), task.getRetryCount(),
                task.getTriggerEventId(), task.getCreatedAt(), record == null ? null : record.getSentAt());
    }

    private WebDtos.DeliveryRecordView toDeliveryRecordView(DeliveryRecord record) {
        return new WebDtos.DeliveryRecordView(
                record.getId(), record.getTaskId(), record.getCampaignId(), record.getUserId(), record.getChannel(),
                record.getStatus(), record.getSentAt(), record.getErrorMsg());
    }

    private WebDtos.ClickView toClickView(ClickEvent click) {
        return new WebDtos.ClickView(click.getId(), click.getTaskId(), click.getUserId(), click.getClickSource(),
                click.getClickTime(), parseJson(click.getProperties()));
    }

    private WebDtos.AttributionView toAttributionView(AttributionRecord record) {
        Campaign campaign = record.getCampaignId() == null ? null : campaignMapper.selectById(record.getCampaignId());
        return new WebDtos.AttributionView(
                record.getId(), record.getUserId(), record.getCampaignId(),
                campaign == null ? (record.getCampaignId() == null ? null : "Campaign " + record.getCampaignId()) : campaign.getName(),
                record.getClickEventId(), record.getTargetEventId(), record.getTaskId(), record.getAttributionModel(),
                record.getAttributionWindowHours(), record.getCreditedAt());
    }

    private Map<String, Object> windowMetrics(Long userId) {
        Map<String, Object> raw;
        try {
            raw = profileService.getWindowMetrics(userId);
        } catch (Exception e) {
            log.info("Window metrics Redis unavailable for user {}, using MySQL fallback", userId);
            List<UserBehaviorSummary> summaries = behaviorSummaryMapper.selectList(
                    new LambdaQueryWrapper<UserBehaviorSummary>().eq(UserBehaviorSummary::getUserId, userId)
                            .orderByDesc(UserBehaviorSummary::getCalculatedAt));
            raw = new LinkedHashMap<>();
            for (UserBehaviorSummary summary : summaries) {
                raw.putIfAbsent(summary.getMetricType(), summary.getMetricValue());
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(canonicalMetric(key), value));
        return normalized;
    }

    private RealtimeResult realtimeMetrics(Long userId) {
        try {
            Map<String, String> raw = profileService.getRealtimeMetrics(userId);
            Map<String, String> normalized = new LinkedHashMap<>();
            raw.forEach((key, value) -> normalized.put(canonicalRealtimeMetric(key), value));
            return new RealtimeResult(normalized, "REDIS", true);
        } catch (Exception ex) {
            log.info("Realtime profile Redis unavailable for user {}, using MySQL fallback", userId);
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            List<UserEvent> events = userEventMapper.selectList(
                    new LambdaQueryWrapper<UserEvent>().eq(UserEvent::getUserId, userId)
                            .ge(UserEvent::getEffectiveEventTime, todayStart));
            Map<String, String> fallback = new LinkedHashMap<>();
            fallback.put("todayViews", String.valueOf(events.stream().filter(e -> "CONTENT_VIEW".equals(e.getEventType())).count()));
            fallback.put("todaySearches", String.valueOf(events.stream().filter(e -> "SEARCH".equals(e.getEventType())).count()));
            fallback.put("cartCount", "0");
            fallback.put("lastActiveAt", events.stream().map(UserEvent::getEffectiveEventTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).map(LocalDateTime::toString).orElse(""));
            return new RealtimeResult(fallback, "MYSQL_FALLBACK", false);
        }
    }

    private List<String> activeTags(Long userId) {
        List<UserTag> rows = userTagMapper.selectList(
                new LambdaQueryWrapper<UserTag>().eq(UserTag::getUserId, userId)
                        .eq(UserTag::getTagValue, "1")
                        .orderByDesc(UserTag::getCalculatedAt));
        return rows.stream().map(UserTag::getTagName).filter(Objects::nonNull).distinct().toList();
    }

    private PerformanceNumbers performanceNumbers(Long campaignId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT target_audience_count, sent_count, delivered_count, clicked_count, "
                            + "converted_count, unsubscribe_count, delivery_rate, click_rate, conversion_rate, "
                            + "unsubscribe_rate, calculated_at FROM campaign_performance_summary WHERE campaign_id = ?",
                    campaignId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                return new PerformanceNumbers(
                        number(row.get("target_audience_count")), number(row.get("sent_count")),
                        number(row.get("delivered_count")), number(row.get("clicked_count")),
                        number(row.get("converted_count")), number(row.get("unsubscribe_count")),
                        decimal(row.get("delivery_rate")), decimal(row.get("click_rate")),
                        decimal(row.get("conversion_rate")), decimal(row.get("unsubscribe_rate")),
                        localDateTime(row.get("calculated_at")));
            }
        } catch (DataAccessException e) {
            log.debug("Performance summary unavailable for campaign {}: {}", campaignId, e.getMessage());
        }

        long sent = count("SELECT COUNT(*) FROM delivery_record WHERE campaign_id = ? AND status = 'SENT'", campaignId);
        long delivered = sent;
        long clicked = count("SELECT COUNT(*) FROM click_event ce JOIN delivery_task dt ON dt.id = ce.task_id "
                + "WHERE dt.campaign_id = ?", campaignId);
        long converted = attributionRecordMapper.selectCount(
                new LambdaQueryWrapper<AttributionRecord>().eq(AttributionRecord::getCampaignId, campaignId));
        long audience = audienceFromDraft(campaignId);
        return new PerformanceNumbers(audience, sent, delivered, clicked, converted, 0,
                ratio(delivered, sent), ratio(clicked, sent), ratio(converted, sent), BigDecimal.ZERO, null);
    }

    private long audienceFromDraft(Long campaignId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT estimated_audience_count FROM campaign_ai_draft "
                            + "WHERE confirmed_campaign_id = ? ORDER BY confirmed_at DESC LIMIT 1", campaignId);
            return rows.isEmpty() ? 0 : number(rows.get(0).get("estimated_audience_count"));
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private WebDtos.ReviewView reviewForCampaign(Long campaignId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT campaign_id, status, model, prompt_version, error_message, failure_code, retryable, "
                            + "retry_count, next_retry_at, updated_at, review_json FROM campaign_ai_review WHERE campaign_id = ?",
                    campaignId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            return new WebDtos.ReviewView(
                    numberObject(row.get("campaign_id")), string(row.get("status")), string(row.get("model")),
                    string(row.get("prompt_version")), string(row.get("error_message")), string(row.get("failure_code")),
                    bool(row.get("retryable")), integer(row.get("retry_count")), localDateTime(row.get("next_retry_at")),
                    localDateTime(row.get("updated_at")), parseJson(string(row.get("review_json"))));
        } catch (DataAccessException e) {
            log.debug("AI review unavailable for campaign {}: {}", campaignId, e.getMessage());
            return null;
        }
    }

    private boolean canReadReview(Campaign campaign, Long operatorId) {
        return campaign.getCreatedBy() != null && campaign.getCreatedBy().equals(operatorId);
    }

    private void requireReviewOwner(Campaign campaign, Long operatorId) {
        if (!canReadReview(campaign, operatorId)) {
            throw new AiForbiddenException("Operator does not own campaign " + campaign.getId());
        }
    }

    private Campaign findCampaign(Long campaignId) {
        Campaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            throw new AiResourceNotFoundException("Campaign not found: " + campaignId);
        }
        return campaign;
    }

    private UserProfile findUser(Long userId) {
        UserProfile profile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, userId));
        if (profile == null) {
            throw new AiResourceNotFoundException("User not found: " + userId);
        }
        return profile;
    }

    private List<WebDtos.TrendPoint> hourlyTrend(String sql, LocalDateTime from, int buckets) {
        Map<String, Long> values = queryBuckets(sql, from);
        LocalDateTime start = from.withMinute(0).withSecond(0).withNano(0);
        List<WebDtos.TrendPoint> output = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            LocalDateTime bucket = start.plusHours(i);
            output.add(new WebDtos.TrendPoint(bucket.format(HOUR_LABEL),
                    values.getOrDefault(bucket.toLocalDate().toString() + " " + String.format("%02d", bucket.getHour()) + ":00:00", 0L)));
        }
        return output;
    }

    private List<WebDtos.TrendPoint> dailyTrend(String sql, LocalDateTime from, int buckets) {
        Map<String, Long> values = queryBuckets(sql, from);
        LocalDate start = from.toLocalDate();
        List<WebDtos.TrendPoint> output = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            LocalDate bucket = start.plusDays(i);
            output.add(new WebDtos.TrendPoint(bucket.format(DAY_LABEL),
                    values.getOrDefault(bucket.toString(), 0L)));
        }
        return output;
    }

    private Map<String, Long> queryBuckets(String sql, LocalDateTime from) {
        try {
            return jdbcTemplate.queryForList(sql, from).stream().collect(Collectors.toMap(
                    row -> string(row.get("bucket")),
                    row -> number(row.get("value")),
                    Long::sum,
                    LinkedHashMap::new));
        } catch (DataAccessException e) {
            log.debug("Trend query failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private long count(String sql, Object... args) {
        try {
            Number value = jdbcTemplate.queryForObject(sql, Number.class, args);
            return value == null ? 0 : value.longValue();
        } catch (DataAccessException e) {
            log.debug("Count query failed: {}", e.getMessage());
            return 0;
        }
    }

    private String mysqlStatus() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (DataAccessException e) {
            return "DOWN";
        }
    }

    private String redisStatus() {
        try {
            if (redissonClient.isShutdown() || redissonClient.isShuttingDown()) {
                return "DOWN";
            }
            redissonClient.getKeys().count();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String kafkaStatus() {
        return "UP";
    }

    private String aiMode() {
        AiFeatureProperties properties = aiProperties.getIfAvailable();
        if (properties == null || !properties.isEnabled()) {
            return "DISABLED";
        }
        return properties.isMockEnabled() ? "MOCK" : "REAL";
    }

    private String piiMode() {
        AiFeatureProperties properties = aiProperties.getIfAvailable();
        if (properties == null || !properties.isEnabled() || properties.getPii() == null
                || !properties.getPii().isEnabled()) {
            return "DISABLED";
        }
        return properties.isMockEnabled() || properties.getPii().isMockEnabled() ? "MOCK" : "REAL";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (!hasText(json)) {
            return Map.of();
        }
        try {
            Object parsed = JsonUtil.fromJson(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            return Map.of("value", parsed);
        } catch (RuntimeException e) {
            return Map.of("raw", json);
        }
    }

    private String canonicalMetric(String key) {
        return switch (key) {
            case "active_7d", "activeDays7d" -> "activeDays7d";
            case "spend_30d", "spend30d" -> "spend30d";
            case "search_1h", "searchCount1h" -> "search1h";
            case "view_7d", "viewCount7d" -> "viewCount7d";
            case "order_30d", "orderCount30d" -> "orderCount30d";
            default -> key;
        };
    }

    private String canonicalRealtimeMetric(String key) {
        return switch (key) {
            case "views" -> "todayViews";
            case "search_count" -> "todaySearches";
            case "cart_count" -> "cartCount";
            case "last_active_at" -> "lastActiveAt";
            case "last_login_at" -> "lastLoginAt";
            default -> key;
        };
    }

    private <T> WebDtos.PageResponse<T> page(List<T> items, long total, int page, int pageSize) {
        return new WebDtos.PageResponse<>(items, page, pageSize, total,
                total == 0 ? 0 : (int) Math.ceil((double) total / pageSize));
    }

    private int safePage(int page) {
        return Math.max(page, 1);
    }

    private int safePageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private LocalDateTime parseDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (RuntimeException ignored) {
            try {
                return LocalDate.parse(value.trim()).atStartOfDay();
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private Long parseLong(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Long numberObject(Object value) {
        return value == null ? null : number(value);
    }

    private Integer integer(Object value) {
        return value == null ? null : (int) number(value);
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? null : "1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString());
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((double) numerator / denominator).setScale(4, RoundingMode.HALF_UP);
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.toString().replace(' ', 'T'));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private BigDecimal decimal(Map<String, Object> values) {
        return decimal(values == null ? null : values.get("value"));
    }

    private record RealtimeResult(Map<String, String> metrics, String source, boolean available) {
    }

    private record PerformanceNumbers(
            long audience,
            long sent,
            long delivered,
            long clicked,
            long converted,
            long unsubscribed,
            BigDecimal deliveryRate,
            BigDecimal clickRate,
            BigDecimal conversionRate,
            BigDecimal unsubscribeRate,
            LocalDateTime calculatedAt
    ) {
        private WebDtos.DeliverySummary toDeliverySummary() {
            return new WebDtos.DeliverySummary(sent, delivered, clicked, converted,
                    deliveryRate, clickRate, conversionRate);
        }

        private WebDtos.PerformanceView toView(Long campaignId) {
            return new WebDtos.PerformanceView(campaignId, audience, sent, delivered, clicked, converted,
                    unsubscribed, deliveryRate, clickRate, conversionRate, unsubscribeRate, calculatedAt);
        }
    }
}
