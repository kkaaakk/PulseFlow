package com.pulseflow.job.handler;

import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.profile.service.ProfileService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WindowMetricJob {

    private final UserBehaviorSummaryMapper summaries;
    private final ProfileService profileService;
    private final PlatformTransactionManager transactionManager;

    @XxlJob("windowMetricJob")
    public void execute() {
        calculate(LocalDateTime.now());
    }

    public void calculate(LocalDateTime now) {
        LocalDateTime calculatedAt = now.truncatedTo(ChronoUnit.SECONDS);
        BehaviorWindow window = new BehaviorWindow(now.toLocalDate());
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        // Keep the bounded INSERT ... SELECT statements from locking source ranges.
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactionTemplate.executeWithoutResult(status -> {
            int active = summaries.summarizeActiveDays(window.activityStart(), window.endExclusive(), calculatedAt);
            int orders = summaries.summarizePaidOrders(window.purchaseStart(), window.endExclusive(), calculatedAt);
            int spend = summaries.summarizeSpend(window.purchaseStart(), window.endExclusive(), calculatedAt);
            log.info("Window metrics refreshed: active={}, orders={}, spend={}", active, orders, spend);
        });
        profileService.invalidateWindowCaches();
    }
}
