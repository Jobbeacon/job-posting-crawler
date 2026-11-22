package org.example.jobpostingcrawler.worker;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.extractor.JobPostingExtractor;
import org.example.jobpostingcrawler.extractor.UrlExtractor;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.fetcher.Fetcher;
import org.example.jobpostingcrawler.messaging.JobPostingPublisher;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.scheduler.service.CrawlCacheService;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.example.jobpostingcrawler.storage.CrawledUrlService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlWorkerPausedDomainTest {

    @Test
    void urlOfAPausedDomainIsDroppedWithoutFetchingOrMarkingItSeen() throws Exception {
        String url = "https://www.topcv.vn/viec-lam/java/1.html";
        CrawlQueueService queue = mock(CrawlQueueService.class);
        CrawlCacheService cache = mock(CrawlCacheService.class);
        Fetcher fetcher = mock(Fetcher.class);
        DomainDelayService delay = mock(DomainDelayService.class);
        DomainConfigProvider provider = mock(DomainConfigProvider.class);
        when(queue.pop()).thenReturn(Optional.of(new CrawlUrl(url, 1)), Optional.empty());
        when(provider.isPaused("topcv_vn")).thenReturn(true);

        CrawlWorker worker = new CrawlWorker(
                new CrawlerProperties(null, null,
                        new CrawlerProperties.WorkerProperties(true, 1, Duration.ofMillis(20), 2), null,
                        List.of(new CrawlerProperties.SourceProperties("TOPCV", true,
                                List.of("https://www.topcv.vn/viec-lam"), "https://www.topcv.vn/viec-lam/", null))),
                queue, cache, mock(CrawledUrlService.class), fetcher, mock(JobPostingExtractor.class),
                mock(UrlExtractor.class), mock(JobPostingPublisher.class), delay, provider, new SimpleMeterRegistry());
        worker.registerMetrics();

        worker.start();
        try {
            verify(provider, timeout(1000)).isPaused("topcv_vn");
            Thread.sleep(50);
        } finally {
            worker.stop();
        }

        verify(fetcher, never()).getDocument(anyString());
        verify(delay, never()).await(anyString());
        verify(cache, never()).markSeenNonJob(anyString());
        verify(cache, never()).markSeenJob(anyString());
        verify(queue, never()).push(any());
    }
}
