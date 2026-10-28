package com.pulseflow.boot.web;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台业务查询入口，统一参数接收和响应封装。
 * REST facade for business-oriented web-console read models. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WebQueryController {

    private final WebQueryService queryService;

    /** 接收控制台请求并返回统一响应：按当天业务边界汇总事件、活跃用户、触达、点击及归因，生成仪表盘指标。 */
    @GetMapping("/dashboard/summary")
    public ApiResponse<WebDtos.DashboardSummary> dashboardSummary() {
        return ApiResponse.success(queryService.dashboardSummary());
    }

    /** 接收控制台请求并返回统一响应：构造最近指定天数的事件、触达与转化趋势，补齐无数据时间桶。 */
    @GetMapping("/dashboard/trends")
    public ApiResponse<WebDtos.DashboardTrends> dashboardTrends(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(queryService.dashboardTrends(days));
    }

    /** 接收控制台请求并返回统一响应：按分页、关键字、状态、创建人和时间范围查询活动列表。 */
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

    /** 接收活动 ID 并将规则、人群快照及触达归因摘要封装为统一响应。 */
    @GetMapping("/campaigns/{campaignId}")
    public ApiResponse<WebDtos.CampaignDetail> campaign(
            @PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignDetail(campaignId, currentOperatorId()));
    }

    /** 接收控制台请求并返回统一响应：汇总活动发送、点击及转化事实，转换为前端绩效视图。 */
    @GetMapping("/campaigns/{campaignId}/performance")
    public ApiResponse<WebDtos.PerformanceView> campaignPerformance(
            @PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignPerformance(campaignId));
    }

    /** 接收控制台请求并返回统一响应：按活动和天数查询触达趋势，生成连续时间序列。 */
    @GetMapping("/campaigns/{campaignId}/performance/trend")
    public ApiResponse<java.util.List<WebDtos.TrendPoint>> campaignPerformanceTrend(
            @PathVariable Long campaignId,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(queryService.campaignDeliveryTrend(campaignId, days));
    }

    /** 接收控制台请求并返回统一响应：复用触达分页查询，固定活动 ID 并应用用户、渠道与状态筛选。 */
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

    /** 接收控制台请求并返回统一响应：按活动 ID 分页读取归因记录，供活动详情展示。 */
    @GetMapping("/campaigns/{campaignId}/attribution")
    public ApiResponse<WebDtos.PageResponse<WebDtos.AttributionView>> campaignAttributions(
            @PathVariable Long campaignId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        return ApiResponse.success(queryService.campaignAttributions(campaignId, page, pageSize));
    }

    /** 接收控制台请求并返回统一响应：按关键字、状态和分页查询用户，并补充标签与窗口指标。 */
    @GetMapping("/users")
    public ApiResponse<WebDtos.PageResponse<WebDtos.UserListItem>> users(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) Integer status) {
        return ApiResponse.success(queryService.listUsers(page, pageSize, keyword, status));
    }

    /** 接收控制台请求并返回统一响应：聚合用户基础资料、实时画像、窗口画像、标签及近期事件。 */
    @GetMapping("/users/{userId}")
    public ApiResponse<WebDtos.UserDetail> user(
            @PathVariable Long userId,
            @RequestParam(name = "eventPage", defaultValue = "1") int eventPage,
            @RequestParam(name = "eventPageSize", defaultValue = "20") int eventPageSize) {
        return ApiResponse.success(queryService.userDetail(userId, eventPage, eventPageSize));
    }

    /** 接收控制台请求并返回统一响应：查询用户基本档案，记录不存在时报告资源未找到。 */
    @GetMapping("/users/{userId}/profile")
    public ApiResponse<WebDtos.UserProfileView> userProfile(@PathVariable Long userId) {
        return ApiResponse.success(queryService.userProfile(userId));
    }

    /** 接收控制台请求并返回统一响应：固定用户 ID 后复用事件分页查询，支持事件类型及时间筛选。 */
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

    /** 接收控制台请求并返回统一响应：按类型、用户与时间区间分页查询事件事实，转换为展示 DTO。 */
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

    /** 接收控制台请求并返回统一响应：按业务事件 ID 查询单条事件，返回规范化时间与属性。 */
    @GetMapping("/events/{eventId}")
    public ApiResponse<WebDtos.EventView> event(@PathVariable String eventId) {
        return ApiResponse.success(queryService.event(eventId));
    }

    /** 接收控制台请求并返回统一响应：按活动、用户、渠道、状态和时间筛选触达任务，并返回分页结果。 */
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

    /** 接收控制台请求并返回统一响应：聚合单个触达任务的发送记录、点击及归因明细。 */
    @GetMapping("/deliveries/{taskId}")
    public ApiResponse<WebDtos.DeliveryDetail> delivery(@PathVariable Long taskId) {
        return ApiResponse.success(queryService.delivery(taskId));
    }

    /** 接收控制台请求并返回统一响应：按活动、用户、归因模型和时间条件分页读取归因事实。 */
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

    /** 接收控制台请求并返回统一响应：按归因 ID 读取并转换单条记录，不存在时报告资源未找到。 */
    @GetMapping("/attributions/{attributionId}")
    public ApiResponse<WebDtos.AttributionView> attribution(@PathVariable Long attributionId) {
        return ApiResponse.success(queryService.attribution(attributionId));
    }

    /** 接收控制台请求并返回统一响应：检测 MySQL、Redis 和 Kafka 可用性，返回控制台需要的系统状态。 */
    @GetMapping("/system/status")
    public ApiResponse<WebDtos.SystemStatus> systemStatus() {
        return ApiResponse.success(queryService.systemStatus());
    }

    /** 从 Sa-Token 会话取得当前操作员 ID，供资源归属检查使用。 */
    private Long currentOperatorId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        } catch (Exception ignored) {
            return null;
        }
    }
}
