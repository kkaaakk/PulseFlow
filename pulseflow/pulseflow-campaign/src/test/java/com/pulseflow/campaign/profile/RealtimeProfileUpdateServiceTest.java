package com.pulseflow.campaign.profile;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeProfileUpdateServiceTest {

    @Test
    void parsesSpaceSeparatedFractionalSecondsFromReplayEvents() {
        assertThat(RealtimeProfileUpdateService.parseEventTime("2026-08-28 16:45:38.425826700"))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 16, 45, 38, 425_826_700));
    }

    @Test
    void parsesLocalDateTimeWithoutSeconds() {
        assertThat(RealtimeProfileUpdateService.parseEventTime("2026-08-28 16:45"))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 16, 45));
    }
}
