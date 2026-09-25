package com.pulseflow.campaign.preview;

import com.pulseflow.campaign.dsl.AudienceCondition;
import com.pulseflow.campaign.dsl.AudienceGroup;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.validation.CampaignFieldRegistry;
import com.pulseflow.campaign.validation.DslToRuleConverter;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.entity.UserTag;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.pulseflow.mapper.UserTagMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqlAudiencePreviewServiceTest {
    @Test
    void returnsEstimatedAudienceAndDataVersionFromProfileFacts() {
        UserProfileMapper profiles = mock(UserProfileMapper.class);
        UserTagMapper tags = mock(UserTagMapper.class);
        UserBehaviorSummaryMapper metrics = mock(UserBehaviorSummaryMapper.class);
        CampaignFieldRegistry fields = new CampaignFieldRegistry();
        fields.init();
        when(profiles.selectList(any())).thenReturn(List.of(
                UserProfile.builder().userId(10L).status(1).build(),
                UserProfile.builder().userId(11L).status(1).build()));
        when(tags.selectList(any())).thenReturn(List.of(
                UserTag.builder().userId(10L).tagName("HIGH_VALUE").tagValue("1").build()));
        CampaignDsl dsl = CampaignDsl.builder().audience(AudienceGroup.builder()
                .logic("AND").conditions(List.of(AudienceCondition.builder()
                        .field("HIGH_VALUE").operator("EQ").valueType("BOOLEAN").value(true).build()))
                .build()).build();

        AudiencePreviewResult result = new SqlAudiencePreviewService(
                profiles, tags, metrics, fields, new DslToRuleConverter(fields)).preview(dsl);

        assertThat(result.getEstimatedCount()).isEqualTo(1L);
        assertThat(result.getDataVersion()).startsWith("profile-");
        assertThat(result.getWarnings()).isEmpty();
    }
}
