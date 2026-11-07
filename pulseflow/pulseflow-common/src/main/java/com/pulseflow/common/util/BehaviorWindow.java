package com.pulseflow.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Calendar windows in the application's local time, also used for effective_event_time. */
public record BehaviorWindow(LocalDate today) {
    public static final String ACTIVE_DAYS = "active_days_7d";
    public static final String ORDER_COUNT = "order_count_30d";
    public static final String SPEND = "spend_30d";

    public LocalDateTime activityStart() {
        return today.minusDays(6).atStartOfDay();
    }

    public LocalDateTime purchaseStart() {
        return today.minusDays(29).atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return today.plusDays(1).atStartOfDay();
    }
}
