package com.pulseflow.campaign.profile;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeProfileUpdateServiceTest {

    /**
     * 验证回放中的空格分隔毫秒时间可被画像更新解析。
     */
    @Test
    void parsesSpaceSeparatedFractionalSecondsFromReplayEvents() {
        assertThat(RealtimeProfileUpdateService.parseEventTime("2026-08-28 16:45:38.425826700"))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 16, 45, 38, 425_826_700));
    }

    /**
     * 验证省略秒数的本地 ISO 时间可被解析。
     */
    @Test
    void parsesLocalDateTimeWithoutSeconds() {
        assertThat(RealtimeProfileUpdateService.parseEventTime("2026-08-28 16:45"))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 16, 45));
    }
}
