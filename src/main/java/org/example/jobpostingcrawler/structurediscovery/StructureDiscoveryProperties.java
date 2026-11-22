package org.example.jobpostingcrawler.structurediscovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * crawler.structure-discovery.* (module is off unless enabled=true and a Gemini API key is set).
 *
 * @param enabled         run discovery for crawl sources whose domain has no ACTIVE configuration
 * @param gemini          LLM settings
 * @param sampleCount     job pages loaded per domain
 * @param llmSampleCount  of those, pages sent to the LLM (the rest only validate the XPaths)
 * @param maxPromptChars  budget of the simplified html per page
 * @param pageSettleTime  wait after DOMContentLoaded for client-side rendering
 * @param headless        Playwright headless mode
 * @param recheckInterval how often sources are checked for a due discovery (new domain, retry after failure)
 * @param runningTimeout  a RUNNING row older than this is considered dead (app stopped mid-run) and re-run
 * @param retryBaseDelay  wait after the 1st failure, doubled after each further failure
 * @param retryMaxDelay   cap of the wait between retries
 */
@ConfigurationProperties(prefix = "crawler.structure-discovery")
public record StructureDiscoveryProperties(
        boolean enabled,
        Gemini gemini,
        int sampleCount,
        int llmSampleCount,
        int maxPromptChars,
        Duration pageSettleTime,
        Boolean headless,
        Duration recheckInterval,
        Duration runningTimeout,
        Duration retryBaseDelay,
        Duration retryMaxDelay
) {
    public StructureDiscoveryProperties {
        gemini = gemini == null ? new Gemini(null, null, null, null) : gemini;
        sampleCount = sampleCount > 0 ? sampleCount : 4;
        llmSampleCount = llmSampleCount > 0 ? llmSampleCount : 2;
        maxPromptChars = maxPromptChars > 0 ? maxPromptChars : 40_000;
        pageSettleTime = pageSettleTime == null ? Duration.ofSeconds(6) : pageSettleTime;
        headless = headless == null ? Boolean.TRUE : headless;
        recheckInterval = recheckInterval == null ? Duration.ofHours(1) : recheckInterval;
        runningTimeout = runningTimeout == null ? Duration.ofMinutes(30) : runningTimeout;
        retryBaseDelay = retryBaseDelay == null ? Duration.ofHours(1) : retryBaseDelay;
        retryMaxDelay = retryMaxDelay == null ? Duration.ofHours(24) : retryMaxDelay;
    }

    /**
     * @param apiKey         one or more keys, comma-separated
     * @param model          Gemini model id
     * @param timeout        per request
     * @param thinkingBudget null = model default, 0 = off
     */
    public record Gemini(String apiKey, String model, Duration timeout, Integer thinkingBudget) {
        public Gemini {
            model = model == null || model.isBlank() ? "gemini-3.1-flash-lite" : model;
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        }
    }

    public static StructureDiscoveryProperties defaults(String apiKey) {
        return of(apiKey, null, 0, 0, true);
    }

    /** Settings for a run outside Spring (CLI, live test); scheduling settings keep their defaults. */
    public static StructureDiscoveryProperties of(String apiKey, String model, int sampleCount, int llmSampleCount,
                                                  boolean headless) {
        return new StructureDiscoveryProperties(true, new Gemini(apiKey, model, null, null), sampleCount,
                llmSampleCount, 0, null, headless, null, null, null, null);
    }
}
