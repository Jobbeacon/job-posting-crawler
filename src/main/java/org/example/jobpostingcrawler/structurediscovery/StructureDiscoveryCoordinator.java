package org.example.jobpostingcrawler.structurediscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.config.CrawlerProperties.SourceProperties;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig.Status;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.example.jobpostingcrawler.structurediscovery.page.PageLoader;
import org.example.jobpostingcrawler.utils.UrlUtils;
import org.jsoup.nodes.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Runs structure discovery for every enabled crawl source whose domain has no ACTIVE row in
 * domain_extractor_config, one domain at a time on its own thread, and stores the outcome:
 * <ul>
 *   <li>at least one field located: ACTIVE, the HTML extractor uses the XPaths right away;</li>
 *   <li>otherwise (LLM error, no field located...): FAILED, retried after 1h, 2h, 4h... capped at 24h.</li>
 * </ul>
 * While the first attempt of a domain is pending the crawler pauses that domain
 * ({@link DomainConfigProvider#isPaused}); its seeds are pushed again once the attempt is over.
 */
@Slf4j
public class StructureDiscoveryCoordinator implements AutoCloseable {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final CrawlerProperties crawlerProperties;
    private final StructureDiscoveryProperties properties;
    private final DomainExtractorConfigRepository repository;
    private final DomainConfigProvider configProvider;
    private final HtmlStructureDiscoveryService discoveryService;
    private final CrawlQueueService queueService;
    private final Supplier<PageLoader> loaderFactory;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Executor executor;
    private final DomainThrottle throttle;
    private Duration pageRateLimitWait = Duration.ofSeconds(15);

    /** Domains queued or running in this process (the DB row is the lock across restarts). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public StructureDiscoveryCoordinator(CrawlerProperties crawlerProperties,
                                         StructureDiscoveryProperties properties,
                                         DomainExtractorConfigRepository repository,
                                         DomainConfigProvider configProvider,
                                         HtmlStructureDiscoveryService discoveryService,
                                         CrawlQueueService queueService,
                                         Supplier<PageLoader> loaderFactory,
                                         ObjectMapper objectMapper,
                                         Clock clock,
                                         Executor executor) {
        this(crawlerProperties, properties, repository, configProvider, discoveryService, queueService,
                loaderFactory, objectMapper, clock, executor, null);
    }

    /**
     * @param throttle the crawler's per-domain schedule (nullable: the source delay is used between page loads)
     */
    public StructureDiscoveryCoordinator(CrawlerProperties crawlerProperties,
                                         StructureDiscoveryProperties properties,
                                         DomainExtractorConfigRepository repository,
                                         DomainConfigProvider configProvider,
                                         HtmlStructureDiscoveryService discoveryService,
                                         CrawlQueueService queueService,
                                         Supplier<PageLoader> loaderFactory,
                                         ObjectMapper objectMapper,
                                         Clock clock,
                                         Executor executor,
                                         DomainThrottle throttle) {
        this.crawlerProperties = crawlerProperties;
        this.properties = properties;
        this.repository = repository;
        this.configProvider = configProvider;
        this.discoveryService = discoveryService;
        this.queueService = queueService;
        this.loaderFactory = loaderFactory;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = executor;
        this.throttle = throttle;
        // from now on domains without configuration wait for their first discovery run
        configProvider.markDiscoveryActive();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        checkSources();
    }

    @Scheduled(fixedDelayString = "${crawler.structure-discovery.recheck-interval:1h}",
            initialDelayString = "${crawler.structure-discovery.recheck-interval:1h}")
    public void recheck() {
        checkSources();
    }

    /** Queues a discovery for each enabled source whose domain is due (see {@link #isDue}). */
    public synchronized void checkSources() {
        if (crawlerProperties.sources() == null) {
            return;
        }
        Instant now = clock.instant();
        for (SourceProperties source : crawlerProperties.sources()) {
            if (!source.enabled()) {
                continue;
            }
            String key = DomainConfigProvider.domainKey(sourceUrl(source));
            if (key == null || inFlight.contains(key)) {
                continue;
            }
            DomainExtractorConfig row;
            try {
                row = repository.findById(key).orElse(null);
            } catch (Exception e) {
                log.warn("[StructureDiscovery] cannot read config of {}: {}", key, e.getMessage());
                continue;
            }
            if (!isDue(row, now)) {
                continue;
            }
            inFlight.add(key);
            log.info("[StructureDiscovery] queued {} (source {})", key, source.name());
            try {
                executor.execute(() -> {
                    try {
                        run(source, key);
                    } finally {
                        inFlight.remove(key);
                    }
                });
            } catch (Exception e) {
                inFlight.remove(key);
                log.warn("[StructureDiscovery] cannot queue {}: {}", key, e.getMessage());
            }
        }
    }

    /**
     * No row: never discovered. ACTIVE: done. RUNNING: only when older than running-timeout (dead run).
     * FAILED, STALE (layout change detected by ExtractionHealthMonitor): when next_retry_at has passed.
     */
    boolean isDue(DomainExtractorConfig row, Instant now) {
        if (row == null) {
            return true;
        }
        return switch (row.getStatus()) {
            case ACTIVE -> false;
            case RUNNING -> row.getStartedAt() == null
                    || !row.getStartedAt().plus(properties.runningTimeout()).isAfter(now);
            case FAILED, STALE -> row.getNextRetryAt() == null || !row.getNextRetryAt().isAfter(now);
        };
    }

    void run(SourceProperties source, String key) {
        try {
            DomainExtractorConfig row = markRunning(source, key);
            long start = System.nanoTime();
            try (PageLoader loader = new PacedPageLoader(loaderFactory.get(), source.delay(), throttle,
                    pageRateLimitWait)) {
                DiscoveryResult result = discoveryService.discover(loader, source.seeds(), source.jobPageUrlPrefix(),
                        null, properties.sampleCount());
                if (result.fields().isEmpty()) {
                    throw new IllegalStateException("No field could be located. " + result.warnings());
                }
                markActive(row, result);
                log.info("[StructureDiscovery] {} ACTIVE in {}s with {} fields{}", key,
                        Duration.ofNanos(System.nanoTime() - start).toSeconds(), result.fields().size(),
                        result.warnings().isEmpty() ? "" : ", warnings: " + result.warnings());
                log.debug("[StructureDiscovery] report of {}:\n{}", key, result.report());
            } catch (Exception e) {
                markFailed(row, e);
            }
        } catch (Exception e) {
            // DB unavailable: nothing was stored, the next recheck tries again
            log.error("[StructureDiscovery] run of {} aborted", key, e);
        } finally {
            configProvider.refresh();
            pushSeeds(source);
        }
    }

    private DomainExtractorConfig markRunning(SourceProperties source, String key) {
        Instant now = clock.instant();
        DomainExtractorConfig row = repository.findById(key).orElseGet(() -> DomainExtractorConfig.builder()
                .domainKey(key)
                .domain(UrlUtils.extractDomain(sourceUrl(source)))
                .build());
        row.setStatus(Status.RUNNING);
        row.setAttempts(row.getAttempts() + 1);
        row.setStartedAt(now);
        row.setFinishedAt(null);
        row.setUpdatedAt(now);
        DomainExtractorConfig saved = repository.save(row);
        configProvider.refresh();
        log.info("[StructureDiscovery] {} RUNNING (attempt {})", key, saved.getAttempts());
        return saved;
    }

    private void markActive(DomainExtractorConfig row, DiscoveryResult result) throws Exception {
        Instant now = clock.instant();
        row.setStatus(Status.ACTIVE);
        row.setXpaths(objectMapper.writeValueAsString(result.toXpathMap()));
        row.setFieldCoverage(objectMapper.writeValueAsString(coverage(result)));
        row.setStaleFields(null);
        row.setStaleSince(null);
        row.setReport(result.report());
        row.setWarnings(objectMapper.writeValueAsString(result.warnings()));
        row.setSampleUrls(objectMapper.writeValueAsString(result.sampleUrls()));
        row.setError(null);
        row.setNextRetryAt(null);
        row.setFinishedAt(now);
        row.setUpdatedAt(now);
        repository.save(row);
    }

    /** Share of the samples where each found field had a value: baseline of ExtractionHealthMonitor. */
    private static Map<String, Double> coverage(DiscoveryResult result) {
        Map<String, Double> coverage = new LinkedHashMap<>();
        result.fields().forEach((field, r) -> coverage.put(field.jsonKey(),
                r.sampleCount() == 0 ? 0.0 : Math.round(100.0 * r.coverage() / r.sampleCount()) / 100.0));
        return coverage;
    }

    /**
     * Never discovered successfully: FAILED, retried with a growing delay. A domain that already has XPaths
     * (re-discovery of a STALE domain) stays STALE and keeps its working XPaths; retried after retry-max-delay.
     */
    private void markFailed(DomainExtractorConfig row, Exception error) {
        Instant now = clock.instant();
        boolean hasXpaths = row.getXpaths() != null && !row.getXpaths().isBlank() && !row.getXpaths().equals("{}");
        Duration wait = hasXpaths ? properties.retryMaxDelay() : retryDelay(row.getAttempts());
        String message = Objects.requireNonNullElse(error.getMessage(), error.toString());
        row.setStatus(hasXpaths ? Status.STALE : Status.FAILED);
        row.setError(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
        row.setNextRetryAt(now.plus(wait));
        row.setFinishedAt(now);
        row.setUpdatedAt(now);
        repository.save(row);
        log.warn("[StructureDiscovery] {} {} (attempt {}), retry in {}: {}", row.getDomainKey(), row.getStatus(),
                row.getAttempts(), wait, message, error);
    }

    /** retry-base-delay * 2^(attempts-1), capped at retry-max-delay: 1h, 2h, 4h, 8h, 16h, 24h, 24h... */
    Duration retryDelay(int attempts) {
        Duration max = properties.retryMaxDelay();
        Duration delay = properties.retryBaseDelay();
        for (int i = 1; i < attempts && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    /** Seeds skipped by the Scheduler while the domain was paused, and a fresh start with new XPaths. */
    private void pushSeeds(SourceProperties source) {
        if (source.seeds() == null) {
            return;
        }
        try {
            source.seeds().forEach(seed -> queueService.push(new CrawlUrl(seed, 0)));
        } catch (Exception e) {
            log.warn("[StructureDiscovery] cannot push seeds of {}: {}", source.name(), e.getMessage());
        }
    }

    private static String sourceUrl(SourceProperties source) {
        if (source.jobPageUrlPrefix() != null && !source.jobPageUrlPrefix().isBlank()) {
            return source.jobPageUrlPrefix();
        }
        List<String> seeds = source.seeds() == null ? List.of() : source.seeds();
        return seeds.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** For tests: shorter wait before loading a throttled page again. */
    void setPageRateLimitWait(Duration wait) {
        this.pageRateLimitWait = wait;
    }

    @Override
    public void close() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    /**
     * Spaces the page loads of a domain like the crawler does: through the crawler's own per-domain schedule
     * when available (crawler workers and discovery then share it), else the source delay between two loads.
     * A page answered with HTTP 429 (the site throttles us) is loaded again after 15s, then 30s.
     */
    static final class PacedPageLoader implements PageLoader {
        private static final int MAX_RATE_LIMIT_RETRIES = 2;

        private final PageLoader delegate;
        private final long delayMs;
        private final DomainThrottle throttle;
        private final long rateLimitWaitMs;
        private boolean first = true;

        PacedPageLoader(PageLoader delegate, Duration delay, DomainThrottle throttle, Duration rateLimitWait) {
            this.delegate = delegate;
            this.delayMs = delay == null ? 0 : delay.toMillis();
            this.throttle = throttle;
            this.rateLimitWaitMs = rateLimitWait.toMillis();
        }

        @Override
        public Document load(String url) {
            for (int attempt = 0; ; attempt++) {
                pace(url);
                try {
                    return delegate.load(url);
                } catch (RuntimeException e) {
                    boolean rateLimited = e.getMessage() != null && e.getMessage().startsWith("HTTP 429");
                    if (!rateLimited || attempt >= MAX_RATE_LIMIT_RETRIES) {
                        throw e;
                    }
                    long waitMs = rateLimitWaitMs * (attempt + 1);
                    log.info("[StructureDiscovery] {} answered HTTP 429, loading it again in {}s", url, waitMs / 1000);
                    sleep(waitMs);
                }
            }
        }

        private void pace(String url) {
            try {
                if (throttle != null) {
                    throttle.await(url);
                } else if (!first && delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
            first = false;
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } catch (Exception e) {
                log.debug("[StructureDiscovery] page loader close failed: {}", e.getMessage());
            }
        }
    }

    /** Per-domain request schedule shared with the crawler (DomainDelayService::await). */
    @FunctionalInterface
    public interface DomainThrottle {
        void await(String url) throws InterruptedException;
    }
}
