package com.pulseflow.ai.infrastructure.observability;

import com.pulseflow.ai.infrastructure.persistence.entity.AiGenerationRecord;
import com.pulseflow.ai.infrastructure.persistence.mapper.AiGenerationRecordMapper;
import com.pulseflow.ai.provider.AiRequest;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.support.AiTaskType;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Persists every AI call to {@code ai_generation_record}.
 *
 * <p>Failures here MUST NOT propagate: AI audit is best-effort, never blocks
 * business flow. UK on {@code request_id} de-duplicates retries.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditService {

    private final AiGenerationRecordMapper recordMapper;

    public void recordSuccess(AiRequest request, AiResponse response, String promptVersion) {
        try {
            AiGenerationRecord rec = AiGenerationRecord.builder()
                    .requestId(request.getRequestId())
                    .operatorId(extractLong(request.getMetadata(), "operatorId"))
                    .taskType(request.getTaskType().name())
                    .provider(response.getProvider())
                    .model(response.getModel())
                    .promptVersion(promptVersion)
                    .sanitizedInputJson(sanitisedInput(request))
                    .structuredOutputJson(truncate(response.getRawContent(), 32_000))
                    .status("SUCCESS")
                    .promptTokens(response.getPromptTokens())
                    .completionTokens(response.getCompletionTokens())
                    .totalTokens(response.getTotalTokens())
                    .latencyMs(response.getLatencyMs())
                    .draftId(extractLong(request.getMetadata(), "draftId"))
                    .campaignId(extractLong(request.getMetadata(), "campaignId"))
                    .createdAt(LocalDateTime.now())
                    .build();
            recordMapper.insert(rec);
        } catch (DuplicateKeyException e) {
            log.info("AI audit record already exists for requestId={}", request.getRequestId());
        } catch (Exception e) {
            log.warn("Failed to persist AI audit record for requestId={}: {}",
                    request.getRequestId(), e.getMessage());
        }
    }

    public void recordFailure(AiRequest request, String promptVersion,
                              String errorCode, String errorMessage) {
        try {
            AiGenerationRecord rec = AiGenerationRecord.builder()
                    .requestId(request.getRequestId())
                    .operatorId(extractLong(request.getMetadata(), "operatorId"))
                    .taskType(request.getTaskType().name())
                    .provider("n/a")
                    .model("n/a")
                    .promptVersion(promptVersion)
                    .sanitizedInputJson(sanitisedInput(request))
                    .status("FAILED")
                    .errorCode(errorCode)
                    .errorMessage(truncate(errorMessage, 480))
                    .draftId(extractLong(request.getMetadata(), "draftId"))
                    .campaignId(extractLong(request.getMetadata(), "campaignId"))
                    .createdAt(LocalDateTime.now())
                    .build();
            recordMapper.insert(rec);
        } catch (Exception e) {
            log.warn("Failed to persist AI failure record for requestId={}: {}",
                    request.getRequestId(), e.getMessage());
        }
    }

    private String sanitisedInput(AiRequest request) {
        // Only metadata + task type + prompt length are stored; never raw prompts
        // which may include aggregated audience metrics.
        Map<String, Object> safe = Map.of(
                "taskType", request.getTaskType().name(),
                "responseSchemaName", request.getResponseSchemaName() == null ? "" : request.getResponseSchemaName(),
                "temperature", request.getTemperature(),
                "maxTokens", request.getMaxTokens(),
                "systemPromptLength", request.getSystemPrompt() == null ? 0 : request.getSystemPrompt().length(),
                "userPromptLength", request.getUserPrompt() == null ? 0 : request.getUserPrompt().length()
        );
        return JsonUtil.toJson(safe);
    }

    private Long extractLong(Map<String, Object> metadata, String key) {
        if (metadata == null) return null;
        Object v = metadata.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
