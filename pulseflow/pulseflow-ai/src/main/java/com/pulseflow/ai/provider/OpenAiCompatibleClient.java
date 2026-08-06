package com.pulseflow.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat completions client.
 *
 * <p>Works with DeepSeek and any provider that follows the
 * {@code POST {base-url}/chat/completions} schema. Only the fields PulseFlow
 * needs are populated; no vendor SDK is introduced.</p>
 *
 * <p>Retry policy (per design §6.4):</p>
 * <ul>
 *   <li>Network timeout → retry once.</li>
 *   <li>5xx → retry once.</li>
 *   <li>429 rate limit → no retry (re-throw immediately).</li>
 *   <li>Other 4xx → no retry.</li>
 * </ul>
 */
@Slf4j
public class OpenAiCompatibleClient implements AiModelClient {

    public static final String PROVIDER_NAME = "openai-compatible";

    private final AiFeatureProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleClient(AiFeatureProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(properties.getTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(properties.getTimeoutSeconds()).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getModel() != null && !properties.getModel().isBlank();
    }

    @Override
    public AiResponse generateStructured(AiRequest request) {
        if (!isAvailable()) {
            throw new AiProviderException(AiErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "OpenAI-compatible client is not fully configured (base-url/api-key/model)");
        }

        Map<String, Object> body = buildRequestBody(request);
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long started = System.currentTimeMillis();
            try {
                String raw = restClient.post()
                        .uri("/chat/completions")
                        .body(JsonUtil.toJson(body))
                        .retrieve()
                        .body(String.class);

                long latency = System.currentTimeMillis() - started;
                return parseResponse(raw, request, latency);
            } catch (ResourceAccessException e) {
                // Network / timeout
                lastFailure = e;
                log.warn("AI provider timeout attempt {}/{}: requestId={} cause={}",
                        attempt, maxAttempts, request.getRequestId(), e.getMessage());
                if (attempt >= maxAttempts) break;
            } catch (HttpClientErrorException.TooManyRequests e) {
                // 429 — never retry
                throw new AiProviderException(AiErrorCode.AI_PROVIDER_RATE_LIMITED,
                        "AI provider rate limited", e);
            } catch (HttpClientErrorException e) {
                // Other 4xx — never retry
                throw new AiProviderException(AiErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "AI provider client error: " + e.getStatusCode(), e);
            } catch (HttpServerErrorException e) {
                // 5xx — retry once
                lastFailure = e;
                log.warn("AI provider 5xx attempt {}/{}: requestId={} status={}",
                        attempt, maxAttempts, request.getRequestId(), e.getStatusCode());
                if (attempt >= maxAttempts) break;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("AI provider unexpected failure attempt {}/{}: requestId={} cause={}",
                        attempt, maxAttempts, request.getRequestId(), e.getMessage());
                if (attempt >= maxAttempts) break;
            }
        }

        throw new AiProviderException(AiErrorCode.AI_PROVIDER_TIMEOUT,
                "AI provider failed after " + maxAttempts + " attempts", lastFailure);
    }

    private Map<String, Object> buildRequestBody(AiRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens());

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));
        body.put("messages", messages);

        // Hint JSON output. Providers that ignore this still return JSON because
        // the prompt demands it; we strip code fences in AiOutputParser.
        body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    private AiResponse parseResponse(String raw, AiRequest request, long latencyMs) {
        if (raw == null || raw.isBlank()) {
            throw new AiProviderException(AiErrorCode.AI_EMPTY_RESPONSE,
                    "AI provider returned empty body for requestId=" + request.getRequestId());
        }
        JsonNode root = JsonUtil.fromJson(raw, JsonNode.class);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiProviderException(AiErrorCode.AI_EMPTY_RESPONSE,
                    "AI provider returned no choices for requestId=" + request.getRequestId());
        }
        String content = choices.get(0).path("message").path("content").asText("");
        String finishReason = choices.get(0).path("finish_reason").asText("stop");
        JsonNode usage = root.path("usage");

        return AiResponse.builder()
                .requestId(request.getRequestId())
                .provider(PROVIDER_NAME)
                .model(properties.getModel())
                .rawContent(content)
                .structuredContent(content)
                .promptTokens(usage.path("prompt_tokens").asInt(0))
                .completionTokens(usage.path("completion_tokens").asInt(0))
                .totalTokens(usage.path("total_tokens").asInt(0))
                .latencyMs(latencyMs)
                .finishReason(finishReason)
                .build();
    }
}
