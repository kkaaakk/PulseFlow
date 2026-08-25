package com.pulseflow.ai.provider;

import com.azure.ai.textanalytics.TextAnalyticsClient;
import com.azure.ai.textanalytics.TextAnalyticsClientBuilder;
import com.azure.ai.textanalytics.models.PiiEntity;
import com.azure.ai.textanalytics.models.PiiEntityCollection;
import com.azure.ai.textanalytics.models.RecognizePiiEntitiesOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.pulseflow.ai.guardrail.PiiDetectionClient;
import com.pulseflow.ai.guardrail.PiiDetectionEntity;
import com.pulseflow.ai.guardrail.PiiDetectionResult;
import com.pulseflow.ai.support.AiPiiGuardrailUnavailableException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Azure AI Language Text PII adapter.
 *
 * <p>Uses the current stable Azure Java Text Analytics client and the text PII
 * endpoint. The Azure response's original entity text is mapped for internal
 * use only; this class never logs it.</p>
 */
public final class AzurePiiDetectionClient implements PiiDetectionClient {

    public static final String PROVIDER_NAME = "azure-ai-language";

    private final TextAnalyticsClient client;
    private final String language;

    public AzurePiiDetectionClient(String endpoint, String apiKey, String language, int timeoutSeconds) {
        this(buildClient(endpoint, apiKey, timeoutSeconds), language);
    }

    /** Visible for deterministic adapter tests without a live Azure service. */
    AzurePiiDetectionClient(TextAnalyticsClient client, String language) {
        this.client = client;
        this.language = language;
    }

    @Override
    public PiiDetectionResult detect(String text) {
        try {
            PiiEntityCollection collection = client.recognizePiiEntities(
                    text, language, new RecognizePiiEntitiesOptions());
            List<PiiDetectionEntity> entities = new ArrayList<>();
            collection.forEach(entity -> entities.add(toEntity(entity)));
            return new PiiDetectionResult(
                    !entities.isEmpty(),
                    entities,
                    collection.getRedactedText(),
                    PROVIDER_NAME);
        } catch (Exception e) {
            // Azure SDK exception messages can contain request/provider details.
            // Keep the outward message deliberately generic and do not log e.
            throw new AiPiiGuardrailUnavailableException(
                    "PII guardrail provider is unavailable", e);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private PiiDetectionEntity toEntity(PiiEntity entity) {
        return new PiiDetectionEntity(
                entity.getCategory() == null ? "Unknown" : entity.getCategory().toString(),
                entity.getText(),
                entity.getConfidenceScore(),
                entity.getOffset(),
                entity.getLength());
    }

    private static TextAnalyticsClient buildClient(String endpoint, String apiKey, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClientOptions options = new HttpClientOptions()
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .setWriteTimeout(timeout)
                .setResponseTimeout(timeout);
        HttpClient httpClient = HttpClient.createDefault(options);
        return new TextAnalyticsClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(apiKey))
                .httpClient(httpClient)
                .buildClient();
    }
}
