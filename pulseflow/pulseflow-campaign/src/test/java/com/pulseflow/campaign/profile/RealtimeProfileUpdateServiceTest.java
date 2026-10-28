package com.pulseflow.campaign.profile;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 RealtimeProfileUpdateService 的业务行为和边界条件。 */
class RealtimeProfileUpdateServiceTest {

    /** 验证回放的空格分隔小数秒时间能正确解析。 */
    @Test
    void parsesSpaceSeparatedFractionalSecondsFromReplayEvents() {
        assertThat(RealtimeProfileUpdateService.parseEventTime("2026-08-28 16:45:38.425826700"))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 16, 45, 38, 425_826_700));
    }

    /** 验证省略秒字段的本地时间能正确解析。 */
    @Test
    void parsesLocalDateTimeWithoutSeconds() {
        assertThat(RealtimeProfileUpdateService.parseEventTime("2026-08-28 16:45"))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 16, 45));
    }
}
