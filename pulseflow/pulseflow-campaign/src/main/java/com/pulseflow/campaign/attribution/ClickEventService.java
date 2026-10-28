package com.pulseflow.campaign.attribution;

import com.pulseflow.entity.ClickEvent;
import com.pulseflow.mapper.ClickEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 保存用户点击事实，供归因与绩效统计使用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventService {

    private final ClickEventMapper clickEventMapper;

    /**
     * 按用户和任务记录点击事实，供后续归因匹配与绩效统计使用。
     *
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
