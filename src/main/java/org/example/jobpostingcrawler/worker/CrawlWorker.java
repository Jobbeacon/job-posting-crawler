package org.example.jobpostingcrawler.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.extractor.JobPostingExtractor;
import org.example.jobpostingcrawler.exception.FetchException;
import org.example.jobpostingcrawler.extractor.UrlExtractor;
import org.example.jobpostingcrawler.fetcher.Fetcher;
import org.example.jobpostingcrawler.messaging.JobPostingPublisher;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.model.JobPosting;
import org.example.jobpostingcrawler.scheduler.service.CrawlCacheService;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.example.jobpostingcrawler.storage.CrawledUrlService;
import org.example.jobpostingcrawler.utils.UrlUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrawlWorker implements SmartLifecycle {

    private final CrawlerProperties properties;
    private final CrawlQueueService queueService;
    private final CrawlCacheService cacheService;
    private final CrawledUrlService crawledUrlService;
    private final Fetcher fetcher;
    private final JobPostingExtractor jobPostingExtractor;
    private final UrlExtractor urlExtractor;
    private final JobPostingPublisher jobPostingPublisher;
    private final DomainDelayService domainDelayService;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private ExecutorService executor;
    private Set<String> allowedDomains = Set.of();

    private Counter urlsCrawledCounter;
    private Counter jobsExtractedCounter;
    private Counter errorsCounter;

    @PostConstruct
    void registerMetrics() {
        urlsCrawledCounter = Counter.builder("crawler.urls.crawled")
                .description("Total number of URLs fetched by the crawler")
                .register(meterRegistry);
        jobsExtractedCounter = Counter.builder("crawler.jobs.extracted")
                .description("Total number of job postings successfully extracted")
                .register(meterRegistry);
        errorsCounter = Counter.builder("crawler.errors")
                .description("Total number of crawl errors")
                .register(meterRegistry);
        Gauge.builder("crawler.workers.active", activeWorkers, AtomicInteger::get)
                .description("Number of workers currently processing a URL")
                .register(meterRegistry);
        Gauge.builder("crawler.queue.size", queueService, service -> (double) service.size())
                .description("Number of URLs pending in the crawl frontier")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        allowedDomains = Set.copyOf(loadAllowedDomains());
        int poolSize = properties.worker().poolSize();
        executor = Executors.newFixedThreadPool(poolSize);
        log.info("Starting crawl worker pool with {} threads", poolSize);
        for (int i = 0; i < poolSize; i++) {
            int workerId = i;
            executor.submit(() -> runCrawlLoop(workerId));
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Crawl worker pool did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // Stop before Redis/RabbitMQ SmartLifecycle beans (default phase 0).
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void runCrawlLoop(int workerId) {
        Thread.currentThread().setName("crawl-worker-" + workerId);
        log.info("Crawl worker {} started", workerId);
        Duration idle = properties.worker().idleInterval();
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    boolean crawled = crawlOnce();
                    if (!crawled) {
                        sleepIfRunning(idle);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (!running.get() || Thread.currentThread().isInterrupted()) {
                        log.debug("Crawl worker {} exiting during shutdown", workerId);
                        break;
                    }
                    log.error("Crawl worker {} failed a cycle", workerId, e);
                    errorsCounter.increment();
                    try {
                        sleepIfRunning(idle);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            fetcher.closeForCurrentThread();
        }
        log.info("Crawl worker {} stopped", workerId);
    }

    private void sleepIfRunning(Duration idle) throws InterruptedException {
        if (!running.get()) {
            return;
        }
        Thread.sleep(idle.toMillis());
    }

    private boolean crawlOnce() throws InterruptedException {
        if (!running.get()) {
            return false;
        }

        Optional<CrawlUrl> next = queueService.pop();
        if (next.isEmpty()) {
            return false;
        }

        activeWorkers.incrementAndGet();
        try {
            CrawlUrl crawlUrl = next.get();
            String url = crawlUrl.url();

            if (cacheService.isSeen(url)) {
                log.debug("Skip cached url={}", url);
                return true;
            }

            domainDelayService.await(url);

            Document document;
            try {
                document = fetcher.getDocument(url);
            } catch (FetchException e) {
                log.warn("Fetch failed url={} status={}", e.getUrl(), e.getStatusCode());
                errorsCounter.increment();
                return true;
            }

            urlsCrawledCounter.increment();

            if (isJobPageUrl(url)) {
                log.info("Extract job by url: {}", url);
                JobPosting jobPosting = jobPostingExtractor.extract(document, url);
                if (jobPosting != null) {
                    crawledUrlService.save(url, jobPosting.getExpiredDate());
                    cacheService.markSeenJob(url);
                    jobPostingPublisher.push(jobPosting);
                    jobsExtractedCounter.increment();
                } else {
                    log.info("Failed to extract job posting, treating as non-job url: {}", url);
                    cacheAsNonJob(url);
                }
            } else {
                log.info("Url is not a job page: {}", url);
                cacheAsNonJob(url);
            }

            List<String> extractedUrls = urlExtractor.extractUrls(document).stream()
                    .filter(extractedUrl -> allowedDomains.contains(UrlUtils.extractDomain(extractedUrl)))
                    .toList();

            int nextDepth = crawlUrl.depth() + 1;
            if (nextDepth > properties.worker().maxDepth()) {
                return true;
            }

            for (String extractedUrl : extractedUrls) {
                if (cacheService.isSeen(extractedUrl)) {
                    continue;
                }
                queueService.push(new CrawlUrl(extractedUrl, nextDepth));
            }
            return true;
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    private Set<String> loadAllowedDomains() {
        Set<String> domains = new HashSet<>();
        if (properties.sources() == null) {
            return domains;
        }

        properties.sources().stream()
                .filter(CrawlerProperties.SourceProperties::enabled)
                .forEach(source -> {
                    if (source.seeds() != null) {
                        source.seeds().forEach(seed -> addDomain(domains, seed));
                    }
                });
        return domains;
    }

    private void addDomain(Set<String> domains, String url) {
        String domain = UrlUtils.extractDomain(url);
        if (domain != null) {
            domains.add(domain);
        }
    }

    private void cacheAsNonJob(String url) {
        if (!isSeedUrl(url)) {
            cacheService.markSeenNonJob(url);
        }
    }

    private boolean isSeedUrl(String url) {
        if (properties.sources() == null) {
            return false;
        }

        return properties.sources().stream()
                .filter(CrawlerProperties.SourceProperties::enabled)
                .map(CrawlerProperties.SourceProperties::seeds)
                .filter(Objects::nonNull)
                .anyMatch(seeds -> seeds.contains(url));
    }

    private boolean isJobPageUrl(String url) {
        if (properties.sources() == null) {
            return false;
        }

        String cleanUrl = UrlUtils.stripWww(url);

        return properties.sources().stream()
                .filter(CrawlerProperties.SourceProperties::enabled)
                .map(CrawlerProperties.SourceProperties::jobPageUrlPrefix)
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .map(UrlUtils::stripWww)
                .anyMatch(cleanUrl::startsWith);
    }
}
