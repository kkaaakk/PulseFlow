package com.pulseflow.boot.web;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST facade for business-oriented web-console read models. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WebQueryController {

    private final WebQueryService queryService;

    /**
     * 返回控制台今日事件、活跃用户、Campaign 和触达转化的汇总指标。
     */
    @GetMapping("/dashboard/summary")
    public ApiResponse<WebDtos.DashboardSummary> dashboardSummary() {
        return ApiResponse.success(queryService.dashboardSummary());
    }

    /**
     * 按请求天数获取日趋势及最近二十四小时事件趋势，具体上限由查询服务限制。
     */
    @GetMapping("/dashboard/trends")
    public ApiResponse<WebDtos.DashboardTrends> dashboardTrends(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(queryService.dashboardTrends(days));
    }

    /**
     * 接收分页与名称、状态、创建者、创建时间条件，返回 Campaign 列表视图。
     */
    @GetMapping("/campaigns")
    public ApiResponse<WebDtos.PageResponse<WebDtos.CampaignListItem>> campaigns(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "createdBy", required = false) Long createdBy,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime) {
        return ApiResponse.success(queryService.listCampaigns(
                page, pageSize, keyword, status, createdBy, startTime, endTime));
    }

    /**
     * 从当前会话提取操作员 ID，并查询指定 Campaign 的组合详情。
     */
    @GetMapping("/campaigns/{campaignId}")
    public ApiResponse<WebDtos.CampaignDetail> campaign(
            @PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignDetail(campaignId, currentOperatorId()));
    }

    /**
     * 返回指定 Campaign 的绩效快照或查询服务提供的降级统计。
     */
    @GetMapping("/campaigns/{campaignId}/performance")
    public ApiResponse<WebDtos.PerformanceView> campaignPerformance(
            @PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignPerformance(campaignId));
    }

    /**
     * 返回指定 Campaign 在所选天数内的每日触达趋势。
     */
    @GetMapping("/campaigns/{campaignId}/performance/trend")
    public ApiResponse<java.util.List<WebDtos.TrendPoint>> campaignPerformanceTrend(
            @PathVariable Long campaignId,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(queryService.campaignDeliveryTrend(campaignId, days));
    }

    /**
     * 在指定 Campaign 下分页查询触达任务，支持用户、渠道与状态筛选。
     */
    @GetMapping("/campaigns/{campaignId}/deliveries")
    public ApiResponse<WebDtos.PageResponse<WebDtos.DeliveryListItem>> campaignDeliveries(
            @PathVariable Long campaignId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.success(queryService.campaignDeliveries(
                campaignId, page, pageSize, userId, channel, status));
    }

    /**
     * 在指定 Campaign 下分页查询归因记录。
     */
    @GetMapping("/campaigns/{campaignId}/attribution")
    public ApiResponse<WebDtos.PageResponse<WebDtos.AttributionView>> campaignAttributions(
            @PathVariable Long campaignId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        return ApiResponse.success(queryService.campaignAttributions(campaignId, page, pageSize));
    }

    /**
     * 按分页、关键词与用户状态查询用户画像列表。
     */
    @GetMapping("/users")
    public ApiResponse<WebDtos.PageResponse<WebDtos.UserListItem>> users(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) Integer status) {
        return ApiResponse.success(queryService.listUsers(page, pageSize, keyword, status));
    }

    /**
     * 返回用户基础画像、实时和窗口指标、标签及指定分页的事件。
     */
    @GetMapping("/users/{userId}")
    public ApiResponse<WebDtos.UserDetail> user(
            @PathVariable Long userId,
            @RequestParam(name = "eventPage", defaultValue = "1") int eventPage,
            @RequestParam(name = "eventPageSize", defaultValue = "20") int eventPageSize) {
        return ApiResponse.success(queryService.userDetail(userId, eventPage, eventPageSize));
    }

    /**
     * 查询指定用户的基础画像视图，不加载完整事件与指标详情。
     */
    @GetMapping("/users/{userId}/profile")
    public ApiResponse<WebDtos.UserProfileView> userProfile(@PathVariable Long userId) {
        return ApiResponse.success(queryService.userProfile(userId));
    }

    /**
     * 分页查询指定用户的事件，支持事件类型和时间范围筛选。
     */
    @GetMapping("/users/{userId}/events")
    public ApiResponse<WebDtos.PageResponse<WebDtos.EventView>> userEvents(
            @PathVariable Long userId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime) {
        return ApiResponse.success(queryService.listUserEvents(
                userId, page, pageSize, eventType, startTime, endTime));
    }

    /**
     * 分页查询全局事件流，支持事件类型、用户及时间范围筛选。
     */
    @GetMapping("/events")
    public ApiResponse<WebDtos.PageResponse<WebDtos.EventView>> events(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime) {
        return ApiResponse.success(queryService.listEvents(
                page, pageSize, eventType, userId, startTime, endTime));
    }

    /**
     * 按业务事件 ID 查询单条事件详情，缺失资源由服务层报错。
     */
    @GetMapping("/events/{eventId}")
    public ApiResponse<WebDtos.EventView> event(@PathVariable String eventId) {
        return ApiResponse.success(queryService.event(eventId));
    }

    /**
     * 分页查询触达任务，支持 Campaign、用户、渠道、状态和创建时间筛选。
     */
    @GetMapping("/deliveries")
    public ApiResponse<WebDtos.PageResponse<WebDtos.DeliveryListItem>> deliveries(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(name = "campaignId", required = false) Long campaignId,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime) {
        return ApiResponse.success(queryService.listDeliveries(
                campaignId, page, pageSize, userId, channel, status, startTime, endTime));
    }

    /**
     * 返回触达任务及其发送记录、点击和归因明细。
     */
    @GetMapping("/deliveries/{taskId}")
    public ApiResponse<WebDtos.DeliveryDetail> delivery(@PathVariable Long taskId) {
        return ApiResponse.success(queryService.delivery(taskId));
    }

    /**
     * 分页查询归因记录，支持 Campaign、用户、模型与起始时间条件。
     */
    @GetMapping("/attributions")
    public ApiResponse<WebDtos.PageResponse<WebDtos.AttributionView>> attributions(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(name = "campaignId", required = false) Long campaignId,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "startTime", required = false) String startTime) {
        return ApiResponse.success(queryService.listAttributions(
                campaignId, page, pageSize, userId, model, startTime));
    }

    /**
     * 按归因记录主键查询单条归因详情。
     */
    @GetMapping("/attributions/{attributionId}")
    public ApiResponse<WebDtos.AttributionView> attribution(@PathVariable Long attributionId) {
        return ApiResponse.success(queryService.attribution(attributionId));
    }

    /**
     * 返回应用及其依赖的当前状态展示值。
     */
    @GetMapping("/system/status")
    public ApiResponse<WebDtos.SystemStatus> systemStatus() {
        return ApiResponse.success(queryService.systemStatus());
    }

    /**
     * 从 Sa-Token 读取数值操作员标识；会话缺失或格式错误时返回 null。
     */
    private Long currentOperatorId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        } catch (Exception ignored) {
            return null;
        }
    }
}
