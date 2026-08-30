package com.pulseflow.boot.web;

import com.pulseflow.common.model.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebQueryControllerTest {

    @Mock private WebQueryService queryService;
    @InjectMocks private WebQueryController controller;

    @Test
    void dashboardUsesTheUnifiedApiResponseEnvelope() {
        WebDtos.DashboardSummary summary = new WebDtos.DashboardSummary(
                10, 3, 2, 4, BigDecimal.ONE, 1, 1, 1);
        when(queryService.dashboardSummary()).thenReturn(summary);

        ApiResponse<WebDtos.DashboardSummary> response = controller.dashboardSummary();

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isSameAs(summary);
        verify(queryService).dashboardSummary();
    }

    @Test
    void campaignListPassesPaginationAndFiltersToTheQueryService() {
        WebDtos.PageResponse<WebDtos.CampaignListItem> page =
                new WebDtos.PageResponse<>(List.of(), 2, 20, 0, 0);
        when(queryService.listCampaigns(2, 20, "recall", "ACTIVE", 1024L, null, null))
                .thenReturn(page);

        ApiResponse<WebDtos.PageResponse<WebDtos.CampaignListItem>> response = controller.campaigns(
                2, 20, "recall", "ACTIVE", 1024L, null, null);

        assertThat(response.getData().page()).isEqualTo(2);
        verify(queryService).listCampaigns(2, 20, "recall", "ACTIVE", 1024L, null, null);
    }
}
