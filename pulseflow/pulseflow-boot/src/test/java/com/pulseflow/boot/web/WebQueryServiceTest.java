package com.pulseflow.boot.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulseflow.entity.Campaign;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebQueryServiceTest {

    @Mock private CampaignMapper campaignMapper;
    @Mock private CampaignRuleMapper campaignRuleMapper;
    @Mock private DeliveryTaskMapper deliveryTaskMapper;
    @Mock private DeliveryRecordMapper deliveryRecordMapper;
    @Mock private ClickEventMapper clickEventMapper;
    @Mock private AttributionRecordMapper attributionRecordMapper;
    @Mock private UserProfileMapper userProfileMapper;
    @Mock private UserEventMapper userEventMapper;
    @Mock private UserBehaviorSummaryMapper behaviorSummaryMapper;
    @Mock private UserTagMapper userTagMapper;
    @Mock private ProfileService profileService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ObjectProvider<com.pulseflow.ai.infrastructure.config.AiFeatureProperties> aiProperties;
    @Mock private RedissonClient redissonClient;

    private WebQueryService service;

    @BeforeEach
    void setUp() {
        service = new WebQueryService(
                campaignMapper, campaignRuleMapper, deliveryTaskMapper, deliveryRecordMapper,
                clickEventMapper, attributionRecordMapper, userProfileMapper, userEventMapper,
                behaviorSummaryMapper, userTagMapper, profileService, jdbcTemplate, aiProperties,
                redissonClient);
    }

    @Test
    void listCampaignsReturnsBusinessRowsAndStablePagination() {
        Campaign campaign = Campaign.builder()
                .id(77L)
                .name("召回 Campaign")
                .status("ACTIVE")
                .triggerType("SCHEDULED")
                .channel("IN_APP")
                .createdAt(LocalDateTime.now())
                .build();
        Page<Campaign> result = new Page<>(2, 2);
        result.setRecords(List.of(campaign));
        result.setTotal(5);
        when(campaignMapper.selectPage(any(), any())).thenReturn(result);

        Map<String, Object> summary = new HashMap<>();
        summary.put("target_audience_count", 120L);
        summary.put("sent_count", 100L);
        summary.put("delivered_count", 98L);
        summary.put("clicked_count", 12L);
        summary.put("converted_count", 3L);
        summary.put("unsubscribe_count", 0L);
        summary.put("delivery_rate", new BigDecimal("0.98"));
        summary.put("click_rate", new BigDecimal("0.12"));
        summary.put("conversion_rate", new BigDecimal("0.03"));
        summary.put("unsubscribe_rate", BigDecimal.ZERO);
        doReturn(List.of(summary)).when(jdbcTemplate)
                .queryForList(contains("campaign_performance_summary"), eq(77L));

        WebDtos.PageResponse<WebDtos.CampaignListItem> response =
                service.listCampaigns(2, 2, "召回", "ACTIVE", null, null, null);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(2);
        assertThat(response.total()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(77L);
            assertThat(item.audience()).isEqualTo(120L);
            assertThat(item.sent()).isEqualTo(100L);
            assertThat(item.clicked()).isEqualTo(12L);
            assertThat(item.converted()).isEqualTo(3L);
        });
    }

    @Test
    void emptyCampaignPageUsesUnifiedEmptyPageResponse() {
        Page<Campaign> result = new Page<>(1, 10);
        result.setRecords(List.of());
        result.setTotal(0);
        when(campaignMapper.selectPage(any(), any())).thenReturn(result);

        WebDtos.PageResponse<WebDtos.CampaignListItem> response =
                service.listCampaigns(1, 10, null, null, null, null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void invalidCampaignIdReturnsNotFound() {
        when(campaignMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.campaignPerformance(404L))
                .isInstanceOf(com.pulseflow.ai.support.AiResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void reviewRejectsAUserWhoDoesNotOwnTheCampaign() {
        Campaign campaign = Campaign.builder().id(77L).createdBy(1024L).build();
        when(campaignMapper.selectById(77L)).thenReturn(campaign);

        assertThatThrownBy(() -> service.campaignReview(77L, 2048L))
                .isInstanceOf(com.pulseflow.ai.support.AiForbiddenException.class)
                .hasMessageContaining("does not own");
    }

    @Test
    void user360MarksRedisFallbackAndKeepsMysqlMetrics() {
        UserProfile profile = UserProfile.builder().userId(1024L).nickname("演示用户").status(1).build();
        when(userProfileMapper.selectOne(any())).thenReturn(profile);
        when(profileService.getRealtimeMetrics(1024L)).thenThrow(new IllegalStateException("redis down"));
        when(profileService.getWindowMetrics(1024L)).thenThrow(new IllegalStateException("redis down"));
        when(userEventMapper.selectList(any())).thenReturn(List.of(UserEvent.builder()
                .eventType("CONTENT_VIEW").effectiveEventTime(LocalDateTime.now()).build()));
        when(behaviorSummaryMapper.selectList(any())).thenReturn(List.of(UserBehaviorSummary.builder()
                .metricType("active_7d").metricValue(new BigDecimal("6")).calculatedAt(LocalDateTime.now()).build()));
        when(userTagMapper.selectList(any())).thenReturn(List.of(UserTag.builder()
                .tagName("HIGH_VALUE").tagValue("1").calculatedAt(LocalDateTime.now()).build()));
        Page<UserEvent> eventPage = new Page<>(1, 20);
        eventPage.setRecords(List.of());
        eventPage.setTotal(0);
        when(userEventMapper.selectPage(any(), any())).thenReturn(eventPage);

        WebDtos.UserDetail detail = service.userDetail(1024L, 1, 20);

        assertThat(detail.realtimeSource()).isEqualTo("MYSQL_FALLBACK");
        assertThat(detail.realtimeAvailable()).isFalse();
        assertThat(detail.realtimeMetrics()).containsEntry("todayViews", "1");
        assertThat(detail.windowMetrics()).containsEntry("activeDays7d", new BigDecimal("6"));
        assertThat(detail.tags()).containsExactly("HIGH_VALUE");
    }
}
