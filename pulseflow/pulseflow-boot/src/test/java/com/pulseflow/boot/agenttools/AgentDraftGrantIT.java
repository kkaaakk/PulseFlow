package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.draft.CampaignDraftService;
import com.pulseflow.campaign.dsl.DslValidationResult;
import com.pulseflow.campaign.preview.*;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="PULSEFLOW_TEST_DOCKER", matches="true")
@Testcontainers
class AgentDraftGrantIT {
    @Container @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pulseflow_grant_test").withUsername("test").withPassword("test");

    @Test void realGrantLockAllowsOnlyOneDraftForConcurrentRetries() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CampaignDraftService drafts = mock(CampaignDraftService.class);
        CampaignDslValidator validator = mock(CampaignDslValidator.class);
        AudiencePreviewService preview = mock(AudiencePreviewService.class);
        when(validator.validate(any())).thenReturn(DslValidationResult.ok(List.of()));
        when(preview.preview(any())).thenReturn(AudiencePreviewResult.builder().estimatedCount(42).build());
        CampaignDraft draft = CampaignDraft.builder().id(77L).operatorId(1024L)
                .validationStatus("VALIDATED").estimatedAudienceCount(42L).build();
        when(drafts.createDraft(anyString(), eq(1024L), isNull(), any(), any(), any())).thenReturn(draft);
        when(drafts.loadDraft(77L, 1024L)).thenReturn(draft);
        AgentDraftService service = new AgentDraftService(jdbc, drafts, validator, preview);
        String id = UUID.randomUUID().toString();
        service.registerOwner(id, 1024L);
        String grant = service.issue(id, 1024L);
        var request = new AgentDraftService.Request(id, AgentDraftServiceTest.proposal(UUID.randomUUID().toString()));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.execute(ignored -> service.create(grant, request)));
            var second = pool.submit(() -> tx.execute(ignored -> service.create(grant, request)));
            assertThat(first.get().draftId()).isEqualTo(77L);
            assertThat(second.get().draftId()).isEqualTo(77L);
        } finally { pool.shutdownNow(); }
        verify(drafts, times(1)).createDraft(anyString(), eq(1024L), isNull(), any(), any(), any());
        verify(drafts, never()).confirmAndCreate(anyLong(), anyLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_rule", Long.class)).isZero();
    }
}
