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
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Gemini implementation (same SDK as Jobbeacon-be). Several API keys can be given comma-separated
 * (GEMINI_API_KEY=key1,key2,key3): see {@link GeminiKeyRotator} for when the next key is used.
 */
@Slf4j
public class GeminiStructureLlmClient implements StructureLlmClient, AutoCloseable {

    private final List<Client> clients;
    private final String model;
    private final Duration timeout;
    private final Integer thinkingBudget;
    private final GeminiKeyRotator rotator;

    /**
     * @param apiKeys        one or more keys, comma-separated
     * @param model          e.g. gemini-3.1-flash-lite
     * @param timeout        per request
     * @param thinkingBudget null = model default, 0 = disable thinking, &gt;0 = token budget
     */
    public GeminiStructureLlmClient(String apiKeys, String model, Duration timeout, Integer thinkingBudget) {
        List<String> keys = apiKeys == null ? List.of() : Arrays.stream(apiKeys.split(","))
                .map(String::trim).filter(k -> !k.isEmpty()).distinct().toList();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Gemini API key is not configured (crawler.structure-discovery.gemini.api-key)");
        }
        this.clients = keys.stream().map(k -> Client.builder().apiKey(k).build()).toList();
        this.rotator = new GeminiKeyRotator(clients.size());
        log.info("[StructureDiscovery] loaded {} Gemini API key(s)", clients.size());
        this.model = model;
        this.timeout = timeout;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    public LlmSelection select(SimplifiedPage page, String url) {
        Content content = Content.builder()
                .role("user")
                .parts(Part.fromText(StructurePrompt.userPrompt(page, url)))
                .build();
        GenerateContentConfig config = buildConfig();
        return rotator.execute("select " + url, key -> {
            long start = System.nanoTime();
            GenerateContentResponse response = clients.get(key).models.generateContent(model, content, config);
            var usage = response.usageMetadata();
            Integer promptTokens = usage.flatMap(GenerateContentResponseUsageMetadata::promptTokenCount).orElse(null);
            Integer outputTokens = usage.flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount).orElse(null);
            String usedModel = response.modelVersion().orElse(model);
            log.info("[StructureDiscovery] LLM ok url={} key#{} model={} promptTokens={} outputTokens={} latencyMs={}",
                    url, key + 1, usedModel, promptTokens, outputTokens, (System.nanoTime() - start) / 1_000_000);
            return StructurePrompt.parse(response.text(), usedModel, promptTokens, outputTokens);
        });
    }

    private GenerateContentConfig buildConfig() {
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
        if (thinkingBudget != null) {
            builder.thinkingConfig(ThinkingConfig.builder().thinkingBudget(thinkingBudget).build());
        }
        return builder.build();
    }

    @Override
    public void close() {
        clients.forEach(Client::close);
    }
}
