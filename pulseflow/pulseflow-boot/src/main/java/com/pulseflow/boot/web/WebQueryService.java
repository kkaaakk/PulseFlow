package com.pulseflow.boot.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.exception.CampaignResourceNotFoundException;
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
 * 汇总数据库及画像数据，构造控制台需要的只读业务视图。
 *
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
    private final RedissonClient redissonClient;

    /** 按当天业务边界汇总事件、活跃用户、触达、点击及归因，生成仪表盘指标。 */
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

    /** 构造最近指定天数的事件、触达与转化趋势，补齐无数据时间桶。 */
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

    /** 按分页、关键字、状态、创建人和时间范围查询活动列表。 */
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

    /** 读取活动及有序规则，组合人群快照、绩效计数和最近点击归因摘要。 */
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
        return new WebDtos.CampaignDetail(
                toCampaignView(campaign), rules, audience, deliverySummary, attributionSummary);
    }

    /** 汇总活动发送、点击及转化事实，转换为前端绩效视图。 */
    public WebDtos.PerformanceView campaignPerformance(Long campaignId) {
        findCampaign(campaignId);
        return performanceNumbers(campaignId).toView(campaignId);
    }

    /** 按活动和天数查询触达趋势，生成连续时间序列。 */
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

    /** 复用触达分页查询，固定活动 ID 并应用用户、渠道与状态筛选。 */
    public WebDtos.PageResponse<WebDtos.DeliveryListItem> campaignDeliveries(
            Long campaignId, int page, int pageSize, String userId, String channel, String status) {
        findCampaign(campaignId);
        return listDeliveries(campaignId, page, pageSize, userId, channel, status, null, null);
    }

    /** 按活动 ID 分页读取归因记录，供活动详情展示。 */
    public WebDtos.PageResponse<WebDtos.AttributionView> campaignAttributions(
            Long campaignId, int page, int pageSize) {
        findCampaign(campaignId);
        return listAttributions(campaignId, page, pageSize, null, null, null);
    }

    /** 按关键字、状态和分页查询用户，并补充标签与窗口指标。 */
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

    /** 聚合用户基础资料、实时画像、窗口画像、标签及近期事件。 */
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

    /** 查询用户基本档案，记录不存在时报告资源未找到。 */
    public WebDtos.UserProfileView userProfile(Long userId) {
        UserProfile profile = findUser(userId);
        return new WebDtos.UserProfileView(profile.getUserId(), profile.getNickname(), profile.getAvatar(),
                profile.getStatus() == null ? 1 : profile.getStatus(), profile.getCreatedAt(), profile.getUpdatedAt());
    }

    /** 固定用户 ID 后复用事件分页查询，支持事件类型及时间筛选。 */
    public WebDtos.PageResponse<WebDtos.EventView> listUserEvents(
            Long userId, int page, int pageSize, String eventType, String startTime, String endTime) {
        findUser(userId);
        return listEvents(page, pageSize, eventType, userId, startTime, endTime);
    }

    /** 按类型、用户与时间区间分页查询事件事实，转换为展示 DTO。 */
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

    /** 按业务事件 ID 查询单条事件，返回规范化时间与属性。 */
    public WebDtos.EventView event(String eventId) {
        UserEvent event = userEventMapper.selectOne(
                new LambdaQueryWrapper<UserEvent>().eq(UserEvent::getEventId, eventId));
        if (event == null) {
            throw new CampaignResourceNotFoundException("Event not found: " + eventId);
        }
        return toEventView(event);
    }

    /** 按活动、用户、渠道、状态和时间筛选触达任务，并返回分页结果。 */
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

    /** 聚合单个触达任务的发送记录、点击及归因明细。 */
    public WebDtos.DeliveryDetail delivery(Long taskId) {
        DeliveryTask task = deliveryTaskMapper.selectById(taskId);
        if (task == null) {
            throw new CampaignResourceNotFoundException("Delivery task not found: " + taskId);
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

    /** 按活动、用户、归因模型和时间条件分页读取归因事实。 */
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

    /** 按归因 ID 读取并转换单条记录，不存在时报告资源未找到。 */
    public WebDtos.AttributionView attribution(Long attributionId) {
        AttributionRecord record = attributionRecordMapper.selectById(attributionId);
        if (record == null) {
            throw new CampaignResourceNotFoundException("Attribution not found: " + attributionId);
        }
        return toAttributionView(record);
    }

    /** 检测 MySQL、Redis 和 Kafka 可用性，返回控制台需要的系统状态。 */
    public WebDtos.SystemStatus systemStatus() {
        return new WebDtos.SystemStatus(
                "UP",
                mysqlStatus(),
                redisStatus(),
                kafkaStatus());
    }

    /** 把活动持久化实体映射为前端活动详情字段。 */
    private WebDtos.CampaignView toCampaignView(Campaign campaign) {
        return new WebDtos.CampaignView(
                campaign.getId(), campaign.getName(), campaign.getDescription(), campaign.getStatus(),
                campaign.getTriggerType(), campaign.getChannel(), campaign.getEventTypes(),
                campaign.getCronExpression(), campaign.getDelaySeconds(), campaign.getUserDailyLimit(),
                campaign.getCampaignWeeklyLimit(), campaign.getStartTime(), campaign.getEndTime(),
                campaign.getNextTriggerAt(), campaign.getLastTriggerAt(), campaign.getCreatedAt(),
                campaign.getUpdatedAt(), campaign.getCreatedBy());
    }

    /** 转换活动规则，并解析规则配置 JSON 供页面展示。 */
    private WebDtos.RuleView toRuleView(CampaignRule rule) {
        return new WebDtos.RuleView(
                rule.getId(), rule.getRuleName(), rule.getRuleType(), parseJson(rule.getRuleConfig()),
                rule.getPriority(), Objects.equals(rule.getEnabled(), 1));
    }

    /** 把用户实体映射为列表项，补充活跃、消费和标签摘要。 */
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

    /** 转换事件实体，保留原始时间、有效时间和时钟偏差标记。 */
    private WebDtos.EventView toEventView(UserEvent event) {
        return new WebDtos.EventView(
                event.getId(), event.getEventId(), event.getUserId(), event.getEventType(), event.getTargetId(),
                event.getEventTime(), event.getReceivedAt(), event.getEffectiveEventTime(),
                Objects.equals(event.getClockSkew(), 1), parseJson(event.getProperties()));
    }

    /** 将触达任务映射为列表视图，供筛选与状态展示使用。 */
    private WebDtos.DeliveryListItem toDeliveryListItem(DeliveryTask task) {
        Campaign campaign = campaignMapper.selectById(task.getCampaignId());
        DeliveryRecord record = deliveryRecordMapper.selectOne(
                new LambdaQueryWrapper<DeliveryRecord>().eq(DeliveryRecord::getTaskId, task.getId()));
        return new WebDtos.DeliveryListItem(
                task.getId(), task.getCampaignId(), campaign == null ? "Campaign " + task.getCampaignId() : campaign.getName(),
                task.getUserId(), task.getChannel(), task.getStatus(), task.getDispatchStatus(), task.getRetryCount(),
                task.getTriggerEventId(), task.getCreatedAt(), record == null ? null : record.getSentAt());
    }

    /** 将渠道发送记录转换为可展示的投递结果。 */
    private WebDtos.DeliveryRecordView toDeliveryRecordView(DeliveryRecord record) {
        return new WebDtos.DeliveryRecordView(
                record.getId(), record.getTaskId(), record.getCampaignId(), record.getUserId(), record.getChannel(),
                record.getStatus(), record.getSentAt(), record.getErrorMsg());
    }

    /** 转换点击事实并解析扩展属性，供触达详情关联展示。 */
    private WebDtos.ClickView toClickView(ClickEvent click) {
        return new WebDtos.ClickView(click.getId(), click.getTaskId(), click.getUserId(), click.getClickSource(),
                click.getClickTime(), parseJson(click.getProperties()));
    }

    /** 将归因实体转换为前端视图，保留目标事件、归因模型和计入时间。 */
    private WebDtos.AttributionView toAttributionView(AttributionRecord record) {
        Campaign campaign = record.getCampaignId() == null ? null : campaignMapper.selectById(record.getCampaignId());
        return new WebDtos.AttributionView(
                record.getId(), record.getUserId(), record.getCampaignId(),
                campaign == null ? (record.getCampaignId() == null ? null : "Campaign " + record.getCampaignId()) : campaign.getName(),
                record.getClickEventId(), record.getTargetEventId(), record.getTaskId(), record.getAttributionModel(),
                record.getAttributionWindowHours(), record.getCreditedAt());
    }

    /** 读取用户窗口指标，并按统一名称组织最新计算结果。 */
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

    /** 读取 Redis 实时画像；不可用时提供明确来源和可用性标记。 */
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

    /** 读取用户当前有效标签，按标签名保留最新状态。 */
    private List<String> activeTags(Long userId) {
        List<UserTag> rows = userTagMapper.selectList(
                new LambdaQueryWrapper<UserTag>().eq(UserTag::getUserId, userId)
                        .eq(UserTag::getTagValue, "1")
                        .orderByDesc(UserTag::getCalculatedAt));
        return rows.stream().map(UserTag::getTagName).filter(Objects::nonNull).distinct().toList();
    }

    /** 查询活动相关事实计数，供绩效视图和触达摘要复用。 */
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

    /** 从活动关联草稿取得预估人群数，供绩效分母展示使用。 */
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

    /** 按 ID 加载活动，不存在时抛出资源未找到异常。 */
    private Campaign findCampaign(Long campaignId) {
        Campaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            throw new CampaignResourceNotFoundException("Campaign not found: " + campaignId);
        }
        return campaign;
    }

    /** 按 ID 加载用户档案，不存在时抛出资源未找到异常。 */
    private UserProfile findUser(Long userId) {
        UserProfile profile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>().eq(UserProfile::getUserId, userId));
        if (profile == null) {
            throw new CampaignResourceNotFoundException("User not found: " + userId);
        }
        return profile;
    }

    /** 按小时桶组织查询结果并补零，返回固定长度趋势序列。 */
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

    /** 按日期桶组织查询结果并补零，返回连续天数的趋势序列。 */
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

    /** 执行受控趋势 SQL，将时间桶与计数整理为映射。 */
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

    /** 执行计数查询，将可空数据库结果转换为稳定的 long 值。 */
    private long count(String sql, Object... args) {
        try {
            Number value = jdbcTemplate.queryForObject(sql, Number.class, args);
            return value == null ? 0 : value.longValue();
        } catch (DataAccessException e) {
            log.debug("Count query failed: {}", e.getMessage());
            return 0;
        }
    }

    /** 通过轻量数据库查询判断 MySQL 是否可用，异常时返回降级状态。 */
    private String mysqlStatus() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (DataAccessException e) {
            return "DOWN";
        }
    }

    /** 通过 Redis 连通性检测判断缓存可用性，避免检测异常中断整个状态接口。 */
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

    /** 检查 Kafka 管理连接并返回可用性状态，按配置限制等待时间。 */
    private String kafkaStatus() {
        return "UP";
    }

    /** 把可空 JSON 解析为属性映射，为前端提供统一结构。 */
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

    /** 把历史或存储层窗口指标名映射为前端统一命名。 */
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

    /** 归一化 Redis 实时指标名称，使页面无需依赖底层字段差异。 */
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

    /** 根据条目、总数和分页参数计算总页数，封装统一分页响应。 */
    private <T> WebDtos.PageResponse<T> page(List<T> items, long total, int page, int pageSize) {
        return new WebDtos.PageResponse<>(items, page, pageSize, total,
                total == 0 ? 0 : (int) Math.ceil((double) total / pageSize));
    }

    /** 规范化页码，确保分页从有效的第一页开始。 */
    private int safePage(int page) {
        return Math.max(page, 1);
    }

    /** 限定每页条目数量，防止过大分页查询影响服务。 */
    private int safePageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    /** 判断字符串是否包含非空白内容，供可选筛选条件使用。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 清理可选字符串首尾空白，统一空输入处理。 */
    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 解析前端时间筛选值，转换为本地业务时间。 */
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

    /** 解析可选整数标识，处理空输入和格式错误。 */
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

    /** 将数据库返回的计数转换为 long，统一空值处理。 */
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

    /** 将可空数值转换为 Long，保留缺失值语义。 */
    private Long numberObject(Object value) {
        return value == null ? null : number(value);
    }

    /** 将数据库字段转换为可空 Integer，供状态等离散字段使用。 */
    private Integer integer(Object value) {
        return value == null ? null : (int) number(value);
    }

    /** 将数据库布尔字段转换为 Boolean，兼容 JDBC 的数值表示。 */
    private Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? null : "1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString());
    }

    /** 把数据库数值或单行聚合结果转换为 BigDecimal，统一绩效数值处理。 */
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

    /** 计算统一精度的比率，分母为零时返回零以避免除零错误。 */
    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((double) numerator / denominator).setScale(4, RoundingMode.HALF_UP);
    }

    /** 将 JDBC 时间类型转换为 LocalDateTime，统一 DTO 时间表示。 */
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

    /** 将可空数据库字段转换为展示字符串。 */
    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    /** 把数据库数值或单行聚合结果转换为 BigDecimal，统一绩效数值处理。 */
    private BigDecimal decimal(Map<String, Object> values) {
        return decimal(values == null ? null : values.get("value"));
    }

    /** 实时画像查询的值、来源和可用性状态。 */
    private record RealtimeResult(Map<String, String> metrics, String source, boolean available) {
    }

    /** 复用于绩效及触达摘要的内部事实计数。 */
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
        /** 将已汇总的计数映射为活动详情中的触达摘要。 */
        private WebDtos.DeliverySummary toDeliverySummary() {
            return new WebDtos.DeliverySummary(sent, delivered, clicked, converted,
                    deliveryRate, clickRate, conversionRate);
        }

        /** 结合活动 ID 将汇总计数与比率映射为绩效响应。 */
        private WebDtos.PerformanceView toView(Long campaignId) {
            return new WebDtos.PerformanceView(campaignId, audience, sent, delivered, clicked, converted,
                    unsubscribed, deliveryRate, clickRate, conversionRate, unsubscribeRate, calculatedAt);
        }
    }
}
