package com.pulseflow.event.consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventConsumerTest {

    @Test
    @DisplayName("Consumer JSON parser keeps a present targetId key with null value")
    @SuppressWarnings("unchecked")
    void parserPreservesPresentNullTargetId() throws Exception {
        EventConsumer consumer = new EventConsumer(null, null, null, null, null);
        Method parseEvent = EventConsumer.class.getDeclaredMethod("parseEvent", String.class);
        parseEvent.setAccessible(true);

        Map<String, Object> parsed = (Map<String, Object>) parseEvent.invoke(
                consumer,
                "{\"eventId\":\"evt-consumer-null-target\",\"targetId\":null}"
        );

        assertThat(parsed).containsKey("targetId");
        assertThat(parsed.get("targetId")).isNull();
    }
}
