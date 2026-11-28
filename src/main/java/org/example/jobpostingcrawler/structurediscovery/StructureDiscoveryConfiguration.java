package org.example.jobpostingcrawler.structurediscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.fetcher.ProxyConfig;
import org.example.jobpostingcrawler.fetcher.ProxyProvider;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.example.jobpostingcrawler.structurediscovery.llm.GeminiStructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.llm.StructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.page.PlaywrightPageLoader;
import org.example.jobpostingcrawler.structurediscovery.simplify.HtmlSimplifier;
import org.example.jobpostingcrawler.worker.DomainDelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executors;

/**
 * Beans of the structure discovery module: active with crawler.structure-discovery.enabled=true and a Gemini
 * API key. Without them the crawler never pauses a domain and domains without configuration use JSON-LD only.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StructureDiscoveryProperties.class)
public class StructureDiscoveryConfiguration {

    private static final String ENABLED = "${crawler.structure-discovery.enabled:false}";
    private static final String HAS_KEY = "'${crawler.structure-discovery.gemini.api-key:}'.trim() != ''";

    @Configuration
    @ConditionalOnExpression(ENABLED + " and " + HAS_KEY)
    static class Active {

        @Bean(destroyMethod = "close")
        public GeminiStructureLlmClient structureLlmClient(StructureDiscoveryProperties properties) {
            return createLlmClient(properties);
        }

        @Bean
        public HtmlStructureDiscoveryService htmlStructureDiscoveryService(StructureLlmClient structureLlmClient,
                                                                           StructureDiscoveryProperties properties) {
            return createService(structureLlmClient, properties);
        }

        @Bean(destroyMethod = "close")
        public StructureDiscoveryCoordinator structureDiscoveryCoordinator(
                CrawlerProperties crawlerProperties,
                StructureDiscoveryProperties properties,
                DomainExtractorConfigRepository repository,
                DomainConfigProvider configProvider,
                HtmlStructureDiscoveryService discoveryService,
                CrawlQueueService queueService,
                ObjectMapper objectMapper,
                DomainDelayService domainDelayService,
                ProxyProvider proxyProvider) {
            return new StructureDiscoveryCoordinator(crawlerProperties, properties, repository, configProvider,
                    discoveryService, queueService,
                    // same proxies as the crawler (the server's own IP may get HTTP 403); a new one on each run
                    () -> {
                        ProxyConfig proxy = proxyProvider.getProxyForCurrentThread();
                        proxyProvider.clearForCurrentThread();
                        return new PlaywrightPageLoader(properties.headless(), properties.pageSettleTime(), proxy);
                    },
                    objectMapper, Clock.systemUTC(),
                    Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "structure-discovery");
                        t.setDaemon(true);
                        return t;
                    }),
                    // same per-domain schedule as the crawl workers: the site is not hit harder while discovering
                    domainDelayService::await);
        }
    }

    @Configuration
    @ConditionalOnExpression(ENABLED + " and !(" + HAS_KEY + ")")
    static class MissingKey {
        MissingKey() {
            log.warn("[StructureDiscovery] enabled but GEMINI_API_KEY is empty: discovery is off, domains without "
                    + "HTML configuration are crawled with JSON-LD only");
        }
    }

    // Factories shared with the CLI (no Spring context needed)

    public static GeminiStructureLlmClient createLlmClient(StructureDiscoveryProperties p) {
        return new GeminiStructureLlmClient(p.gemini().apiKey(), p.gemini().model(), p.gemini().timeout(),
                p.gemini().thinkingBudget());
    }

    public static HtmlStructureDiscoveryService createService(StructureLlmClient llm, StructureDiscoveryProperties p) {
        HtmlSimplifier.Options defaults = HtmlSimplifier.Options.defaults();
        HtmlSimplifier simplifier = new HtmlSimplifier(new HtmlSimplifier.Options(
                p.maxPromptChars(), defaults.maxTextLength(), defaults.maxRepeatedSiblings()));
        return new HtmlStructureDiscoveryService(llm, simplifier, p.llmSampleCount());
    }
}
