package com.pulseflow.campaign.preview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.campaign.dsl.AudienceCondition;
import com.pulseflow.campaign.dsl.AudienceGroup;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.validation.CampaignFieldRegistry;
import com.pulseflow.campaign.validation.DslToRuleConverter;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.mapper.UserProfileMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqlAudiencePreviewServiceTest {
    /**
     * 验证人群预估来自画像事实并携带数据版本。
     */
    @Test
    void returnsEstimatedAudienceAndDataVersionFromProfileFacts() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserProfile.class);
        UserProfileMapper profiles = mock(UserProfileMapper.class);
        DecisionEngine decision = mock(DecisionEngine.class);
        CampaignFieldRegistry fields = new CampaignFieldRegistry();
        fields.init();
        when(profiles.selectList(any())).thenReturn(List.of(
                UserProfile.builder().userId(10L).status(1).build(),
                UserProfile.builder().userId(11L).status(1).build()));
        when(decision.matchScheduledAudience(eq(List.of(10L, 11L)), any(), any())).thenReturn(List.of(10L));
        CampaignDsl dsl = CampaignDsl.builder().audience(AudienceGroup.builder()
                .logic("AND").conditions(List.of(AudienceCondition.builder()
                        .field("activeDays7d").operator("GTE").valueType("INTEGER").value(2).build()))
                .build()).build();

        AudiencePreviewResult result = new SqlAudiencePreviewService(
                profiles, fields, new DslToRuleConverter(fields), decision).preview(dsl);

        assertThat(result.getEstimatedCount()).isEqualTo(1L);
        assertThat(result.getDataVersion()).startsWith("profile-");
        assertThat(result.getWarnings()).isEmpty();
    }
}
