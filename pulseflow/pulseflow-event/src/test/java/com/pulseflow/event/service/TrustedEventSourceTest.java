package com.pulseflow.event.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TrustedEventSourceTest {
    @Test void emptyConfigurationAlwaysFailsClosed() {
        assertThat(new TrustedEventSource("").authenticates("")).isFalse();
        assertThat(new TrustedEventSource("").authenticates(null)).isFalse();
    }
    @Test void onlyExactMachineTokenAuthenticates() {
        String token = "test-only-machine-token-at-least-32-characters";
        TrustedEventSource source = new TrustedEventSource(token);
        assertThat(source.authenticates(token)).isTrue();
        assertThat(source.authenticates(token + "x")).isFalse();
        assertThat(source.authenticates(null)).isFalse();
    }
    @Test void weakConfigurationIsRejected() {
        assertThatThrownBy(() -> new TrustedEventSource("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
