package org.example.jobpostingcrawler.structurediscovery.llm;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Gemini implementation (same SDK as Jobbeacon-be). Several API keys can be given comma-separated
 * (GEMINI_API_KEY=key1,key2,key3), and several models: see {@link GeminiKeyRotator} for when the next one is used.
 */
@Slf4j
public class GeminiStructureLlmClient implements StructureLlmClient, AutoCloseable {

    private final List<Client> clients;
    private final List<String> models;
    private final Duration timeout;
    private final Integer thinkingBudget;
    private final GeminiKeyRotator rotator;

    /**
     * @param apiKeys        one or more keys, comma-separated
     * @param models         one or more models, comma-separated, best first
     * @param timeout        per request
     * @param thinkingBudget null = model default, 0 = as little thinking as possible, &gt;0 = token budget (Gemini 2.5)
     */
    public GeminiStructureLlmClient(String apiKeys, String models, Duration timeout, Integer thinkingBudget) {
        List<String> keys = split(apiKeys);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Gemini API key is not configured (crawler.structure-discovery.gemini.api-key)");
        }
        this.clients = keys.stream().map(k -> Client.builder().apiKey(k).build()).toList();
        this.rotator = new GeminiKeyRotator(clients.size());
        log.info("[StructureDiscovery] loaded {} Gemini API key(s)", clients.size());
        this.models = split(models);
        if (this.models.isEmpty()) {
            throw new IllegalArgumentException("No Gemini model is configured (crawler.structure-discovery.gemini.model)");
        }
        this.timeout = timeout;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    public LlmSelection select(SimplifiedPage page, String url) {
        Content content = Content.builder()
                .role("user")
                .parts(Part.fromText(StructurePrompt.userPrompt(page, url)))
                .build();
        return rotator.execute("select " + url, models, (key, model) -> {
            long start = System.nanoTime();
            GenerateContentResponse response = clients.get(key).models.generateContent(model, content, buildConfig(model));
            var usage = response.usageMetadata();
            Integer promptTokens = usage.flatMap(GenerateContentResponseUsageMetadata::promptTokenCount).orElse(null);
            Integer outputTokens = usage.flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount).orElse(null);
            String usedModel = response.modelVersion().orElse(model);
            log.info("[StructureDiscovery] LLM ok url={} key#{} model={} promptTokens={} outputTokens={} latencyMs={}",
                    url, key + 1, usedModel, promptTokens, outputTokens, (System.nanoTime() - start) / 1_000_000);
            return StructurePrompt.parse(response.text(), usedModel, promptTokens, outputTokens);
        });
    }

    private static List<String> split(String values) {
        return values == null ? List.of() : Arrays.stream(values.split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }

    private GenerateContentConfig buildConfig(String model) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(StructurePrompt.SYSTEM_INSTRUCTION)))
                .responseMimeType("application/json")
                .responseSchema(StructurePrompt.responseSchema())
                .temperature(0f)
                .candidateCount(1)
                .maxOutputTokens(4096)
                .httpOptions(HttpOptions.builder()
                        .timeout(Math.toIntExact(timeout.toMillis()))
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build());
        // Gemini 2.5 takes a budget, Gemini 3 a level, Gemma neither; a setting the model does not know is a 400.
        if (thinkingBudget != null && model.startsWith("gemini-2.5")) {
            builder.thinkingConfig(ThinkingConfig.builder().thinkingBudget(thinkingBudget).build());
        } else if (thinkingBudget != null && thinkingBudget == 0 && model.startsWith("gemini-3")) {
            builder.thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.MINIMAL).build());
        }
        return builder.build();
    }

    @Override
    public void close() {
        clients.forEach(Client::close);
    }
}
