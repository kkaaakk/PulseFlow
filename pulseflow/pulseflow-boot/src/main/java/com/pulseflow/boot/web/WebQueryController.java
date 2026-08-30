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

    @GetMapping("/dashboard/summary")
    public ApiResponse<WebDtos.DashboardSummary> dashboardSummary() {
        return ApiResponse.success(queryService.dashboardSummary());
    }

    @GetMapping("/dashboard/trends")
    public ApiResponse<WebDtos.DashboardTrends> dashboardTrends(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(queryService.dashboardTrends(days));
    }

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

    @GetMapping("/campaigns/{campaignId}")
    public ApiResponse<WebDtos.CampaignDetail> campaign(
            @PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignDetail(campaignId, currentOperatorId()));
    }

    @GetMapping("/campaigns/{campaignId}/performance")
    public ApiResponse<WebDtos.PerformanceView> campaignPerformance(
            @PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignPerformance(campaignId));
    }

    @GetMapping("/campaigns/{campaignId}/performance/trend")
    public ApiResponse<java.util.List<WebDtos.TrendPoint>> campaignPerformanceTrend(
            @PathVariable Long campaignId,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(queryService.campaignDeliveryTrend(campaignId, days));
    }

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

    @GetMapping("/campaigns/{campaignId}/attribution")
    public ApiResponse<WebDtos.PageResponse<WebDtos.AttributionView>> campaignAttributions(
            @PathVariable Long campaignId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        return ApiResponse.success(queryService.campaignAttributions(campaignId, page, pageSize));
    }

    @GetMapping("/campaigns/{campaignId}/review")
    public ApiResponse<WebDtos.ReviewView> campaignReview(@PathVariable Long campaignId) {
        return ApiResponse.success(queryService.campaignReview(campaignId, currentOperatorId()));
    }

    @GetMapping("/users")
    public ApiResponse<WebDtos.PageResponse<WebDtos.UserListItem>> users(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) Integer status) {
        return ApiResponse.success(queryService.listUsers(page, pageSize, keyword, status));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<WebDtos.UserDetail> user(
            @PathVariable Long userId,
            @RequestParam(name = "eventPage", defaultValue = "1") int eventPage,
            @RequestParam(name = "eventPageSize", defaultValue = "20") int eventPageSize) {
        return ApiResponse.success(queryService.userDetail(userId, eventPage, eventPageSize));
    }

    @GetMapping("/users/{userId}/profile")
    public ApiResponse<WebDtos.UserProfileView> userProfile(@PathVariable Long userId) {
        return ApiResponse.success(queryService.userProfile(userId));
    }

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

    @GetMapping("/events/{eventId}")
    public ApiResponse<WebDtos.EventView> event(@PathVariable String eventId) {
        return ApiResponse.success(queryService.event(eventId));
    }

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

    @GetMapping("/deliveries/{taskId}")
    public ApiResponse<WebDtos.DeliveryDetail> delivery(@PathVariable Long taskId) {
        return ApiResponse.success(queryService.delivery(taskId));
    }

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

    @GetMapping("/attributions/{attributionId}")
    public ApiResponse<WebDtos.AttributionView> attribution(@PathVariable Long attributionId) {
        return ApiResponse.success(queryService.attribution(attributionId));
    }

    @GetMapping("/system/status")
    public ApiResponse<WebDtos.SystemStatus> systemStatus() {
        return ApiResponse.success(queryService.systemStatus());
    }

    private Long currentOperatorId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        } catch (Exception ignored) {
            return null;
        }
    }
}
