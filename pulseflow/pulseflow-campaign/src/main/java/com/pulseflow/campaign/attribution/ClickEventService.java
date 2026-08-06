package com.pulseflow.campaign.attribution;

import com.pulseflow.entity.ClickEvent;
import com.pulseflow.mapper.ClickEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventService {

    private final ClickEventMapper clickEventMapper;

    /**
     * Record a user click event.
     */
    public void recordClick(Long userId, Long taskId, String clickSource) {
        ClickEvent click = ClickEvent.builder()
                .userId(userId)
                .taskId(taskId)
                .clickTime(LocalDateTime.now())
                .clickSource(clickSource)
                .properties("{}")
                .build();

        clickEventMapper.insert(click);
        log.info("Click event recorded: userId={}, taskId={}", userId, taskId);
    }
}
