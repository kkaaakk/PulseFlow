package com.pulseflow.campaign.attribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseflow.entity.ClickEvent;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.entity.UserEvent;
import com.pulseflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Promotes canonical, authenticated behavior events into validated marketing facts. */
@Service
@RequiredArgsConstructor
public class ClickEventService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ClickEventMapper clickEventMapper;
    private final UserEventMapper userEventMapper;
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DataCompensationTaskMapper compensationTaskMapper;

    public enum Result { ORDINARY, VALID, REJECTED, PENDING }

    /** Joins the event transaction; exceptions escape to roll back every MySQL write. */
    @Transactional(rollbackFor = Exception.class)
    public Result recordClick(UserEvent event) {
        if (!"CLICK".equals(event.getEventType())) return Result.ORDINARY;
        if ("VALID".equals(event.getMarketingClickStatus())) return Result.VALID;
        if ("REJECTED".equals(event.getMarketingClickStatus())) return Result.REJECTED;

        JsonNode properties;
        try {
            properties = JSON.readTree(event.getProperties() == null ? "{}" : event.getProperties());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return reject(event, "INVALID_PROPERTIES");
        }
        if (properties == null || !properties.isObject()) return reject(event, "INVALID_PROPERTIES");
        if (!properties.has("taskId")) return Result.ORDINARY;
        JsonNode taskId = properties.get("taskId");
        if (!taskId.isIntegralNumber() || !taskId.canConvertToLong() || taskId.longValue() <= 0) {
            return reject(event, "INVALID_TASK_ID");
        }
        if (!Integer.valueOf(1).equals(event.getMarketingTrusted())) return reject(event, "UNTRUSTED_SOURCE");
        JsonNode source = properties.get("clickSource");
        if (source != null && (!source.isTextual() || source.textValue().isBlank()
                || source.textValue().length() > 64)) return reject(event, "INVALID_CLICK_SOURCE");

        DeliveryTask task = deliveryTaskMapper.selectById(taskId.longValue());
        if (task == null) return reject(event, "TASK_NOT_FOUND");
        if (!Objects.equals(task.getUserId(), event.getUserId())) return reject(event, "TASK_USER_MISMATCH");
        if ("CANCELLED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
            return reject(event, "TASK_NOT_SENT");
        }
        if (!"SENT".equals(task.getStatus()) || task.getSentAt() == null) {
            // Channel receipt and SENT status may become visible separately.
            update(event, "PENDING", "DELIVERY_NOT_VISIBLE");
            compensationTaskMapper.ensureMarketingClickPending(event.getEventId());
            return Result.PENDING;
        }
        if (event.getEffectiveEventTime().isBefore(task.getSentAt())) {
            return reject(event, "CLICK_BEFORE_SEND");
        }
        clickEventMapper.insertCanonical(ClickEvent.builder()
                .sourceEventId(event.getEventId()).userId(event.getUserId()).taskId(task.getId())
                .clickTime(event.getEffectiveEventTime())
                .clickSource(source == null ? task.getChannel() : source.textValue())
                .properties(event.getProperties()).build());
        update(event, "VALID", null);
        return Result.VALID;
    }

    /** Locks the original event, so replay cannot upgrade a forged duplicate payload. */
    @Transactional(rollbackFor = Exception.class)
    public Result retry(String eventId) {
        UserEvent event = userEventMapper.selectByEventIdForUpdate(eventId);
        if (event == null) throw new IllegalStateException("canonical_click_missing");
        return recordClick(event);
    }

    /** Infrastructure failures must never turn a click into a business rejection. */
    @Transactional(rollbackFor = Exception.class)
    public void exhaustVisibilityRetries(String eventId) {
        UserEvent event = userEventMapper.selectByEventIdForUpdate(eventId);
        if (event != null && "PENDING".equals(event.getMarketingClickStatus())) {
            reject(event, "DELIVERY_NOT_VISIBLE_EXHAUSTED");
        }
    }

    private Result reject(UserEvent event, String reason) {
        update(event, "REJECTED", reason);
        return Result.REJECTED;
    }

    private void update(UserEvent event, String status, String reason) {
        userEventMapper.updateMarketingClick(event.getEventId(), status, reason);
        event.setMarketingClickStatus(status);
        event.setMarketingClickReason(reason);
    }
}
