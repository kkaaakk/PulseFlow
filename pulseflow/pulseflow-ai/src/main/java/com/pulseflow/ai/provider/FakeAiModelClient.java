package com.pulseflow.ai.provider;

import com.pulseflow.ai.support.AiTaskType;
import com.pulseflow.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Mock {@link AiModelClient} used for:
 * <ul>
 *   <li>Local demo without an API key.</li>
 *   <li>Unit tests.</li>
 *   <li>CI environment.</li>
 * </ul>
 *
 * <p>Fixtures live under {@code classpath:fake-ai/} keyed by task type.
 * Each fixture is a JSON document matching the corresponding v1 schema.
 * The fake client never performs a network call and always returns
 * {@code isAvailable()=true}.</p>
 */
@Slf4j
public class FakeAiModelClient implements AiModelClient {

    public static final String PROVIDER_NAME = "fake";

    private static final Map<AiTaskType, String> FIXTURE_PATHS = Map.of(
            AiTaskType.PARSE_DSL, "fake-ai/parse-dsl.json",
            AiTaskType.INSIGHT,   "fake-ai/insight.json",
            AiTaskType.CONTENT,   "fake-ai/content.json",
            AiTaskType.REVIEW,    "fake-ai/review.json"
    );

    @Override
    public AiResponse generateStructured(AiRequest request) {
        long started = System.currentTimeMillis();
        String fixture = loadFixture(request.getTaskType());

        // Dynamically update sendAt to now+1h so demo fixtures never expire.
        if (request.getTaskType() == AiTaskType.PARSE_DSL) {
            fixture = replaceSendAt(fixture);
        }

        return AiResponse.builder()
                .requestId(request.getRequestId())
                .provider(PROVIDER_NAME)
                .model("fake-mock-v1")
                .rawContent(fixture)
                .structuredContent(fixture)
                .promptTokens(estimateTokens(request))
                .completionTokens(estimateTokens(fixture))
                .totalTokens(estimateTokens(request) + estimateTokens(fixture))
                .latencyMs(System.currentTimeMillis() - started)
                .finishReason("stop")
                .build();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private String loadFixture(AiTaskType taskType) {
        String path = FIXTURE_PATHS.get(taskType);
        if (path == null) {
            throw new IllegalArgumentException("No fake fixture for task type: " + taskType);
        }
        try {
            ClassPathResource res = new ClassPathResource(path);
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Fallback minimal in-memory fixture so unit tests still pass if
            // resource loading is unavailable (defensive).
            log.warn("Fake fixture {} not loadable, using inline fallback: {}", path, e.getMessage());
            return inlineFallback(taskType);
        }
    }

    private String replaceSendAt(String json) {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
        String futureStr = future.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        return json.replaceAll("\"sendAt\"\\s*:\\s*\"[^\"]*\"", "\"sendAt\": \"" + futureStr + "\"");
    }

    private String inlineFallback(AiTaskType taskType) {
        return switch (taskType) {
            case PARSE_DSL -> """
                    {"schemaVersion":1,"campaignName":"fake","objective":"CONVERSION",
                     "audience":{"logic":"AND","conditions":[]},
                     "channel":"IN_APP","schedule":{"type":"ONCE","sendAt":"2026-08-03T20:00:00+08:00","timezone":"Asia/Shanghai"},
                     "frequencyCap":{"maxTimes":1,"windowHours":24},
                     "promotionFacts":[],"missingFields":[],"warnings":[]}""";
            case INSIGHT -> """
                    {"summary":"fake insight","findings":[],"strategySuggestions":[],"risks":[]}""";
            case CONTENT -> """
                    {"variants":[{"type":"DIRECT_BENEFIT","title":"t","body":"b","strategy":"s"}]}""";
            case REVIEW -> """
                    {"summary":"fake review","highlights":[],"problems":[],"nextActions":[],"limitations":[]}""";
        };
    }

    private int estimateTokens(Object obj) {
        if (obj == null) return 0;
        String text = (obj instanceof String s) ? s : JsonUtil.toJson(obj);
        // Rough: 4 chars per token
        return Math.max(1, text.length() / 4);
    }
}
