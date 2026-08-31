package org.example.jobpostingcrawler.worker;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.extractor.JobPostingExtractor;
import org.example.jobpostingcrawler.extractor.UrlExtractor;
import org.example.jobpostingcrawler.fetcher.Fetcher;
import org.example.jobpostingcrawler.messaging.JobPostingPublisher;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.model.JobPosting;
import org.example.jobpostingcrawler.scheduler.service.CrawlCacheService;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CrawlWorker {

    private final CrawlerProperties properties;
    private final CrawlQueueService queueService;
    private final CrawlCacheService cacheService;
    private final Fetcher fetcher;
    private final JobPostingExtractor jobPostingExtractor;
    private final UrlExtractor urlExtractor;
    private final JobPostingPublisher jobPostingPublisher;

    private ExecutorService executor;

    @PostConstruct
    public void startWorkers() {
        int poolSize = properties.worker().poolSize();
        executor = Executors.newFixedThreadPool(poolSize);
        log.info("Starting crawl worker pool with {} threads", poolSize);
        for (int i = 0; i < poolSize; i++) {
            int workerId = i;
            executor.submit(() -> runCrawlLoop(workerId));
        }
    }

    @PreDestroy
    public void stopWorkers() {
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

    private void runCrawlLoop(int workerId) {
        Thread.currentThread().setName("crawl-worker-" + workerId);
        log.info("Crawl worker {} started", workerId);
        Duration idle = properties.worker().idleInterval();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean crawled = crawlOnce();
                if (!crawled) {
                    Thread.sleep(idle.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Crawl worker {} failed a cycle", workerId, e);
            }
        }
        log.info("Crawl worker {} stopped", workerId);
    }

    private boolean crawlOnce() {
        Optional<CrawlUrl> next = queueService.pop();
        if (next.isEmpty()) {
            return false;
        }

        CrawlUrl crawlUrl = next.get();
        String url = crawlUrl.url();

        if (cacheService.isSeen(url)) {
            log.debug("Skip cached url={}", url);
            return true;
        }

        var document = fetcher.getDocument(url);
        JobPosting jobPosting = jobPostingExtractor.extract(document, url);
        if (jobPosting != null) {
            jobPostingPublisher.push(jobPosting);
            cacheService.markSeen(url);
        }

        List<String> extractedUrls = urlExtractor.extractUrls(document);
        int nextDepth = crawlUrl.depth() + 1;
        for (String extractedUrl : extractedUrls) {
            if (cacheService.isSeen(extractedUrl)) {
                continue;
            }
            queueService.push(new CrawlUrl(extractedUrl, nextDepth));
        }
        return true;
    }
}
