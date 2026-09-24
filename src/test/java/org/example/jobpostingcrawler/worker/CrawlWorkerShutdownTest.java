package org.example.jobpostingcrawler.worker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.extractor.JobPostingExtractor;
import org.example.jobpostingcrawler.extractor.UrlExtractor;
import org.example.jobpostingcrawler.fetcher.Fetcher;
import org.example.jobpostingcrawler.messaging.JobPostingPublisher;
import org.example.jobpostingcrawler.scheduler.service.CrawlCacheService;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.example.jobpostingcrawler.storage.CrawledUrlService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawlWorkerShutdownTest {

    @Mock
    private CrawlQueueService queueService;
    @Mock
    private CrawlCacheService cacheService;
    @Mock
    private CrawledUrlService crawledUrlService;
    @Mock
    private Fetcher fetcher;
    @Mock
    private JobPostingExtractor jobPostingExtractor;
    @Mock
    private UrlExtractor urlExtractor;
    @Mock
    private JobPostingPublisher jobPostingPublisher;
    @Mock
    private DomainDelayService domainDelayService;

    private CrawlWorker worker;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        CrawlerProperties properties = new CrawlerProperties(
                new CrawlerProperties.SchedulerProperties(false, Duration.ofMinutes(30)),
                new CrawlerProperties.DedupProperties(Duration.ofDays(7), "test-bloom-filter"),
                new CrawlerProperties.WorkerProperties(true, 1, Duration.ofMillis(40), 2),
                new CrawlerProperties.RabbitProperties("ex", "q", "rk"),
                List.of()
        );
        worker = new CrawlWorker(
                properties,
                queueService,
                cacheService,
                crawledUrlService,
                fetcher,
                jobPostingExtractor,
                urlExtractor,
                jobPostingPublisher,
                domainDelayService,
                new SimpleMeterRegistry()
        );
        worker.registerMetrics();

        Logger logger = (Logger) LoggerFactory.getLogger(CrawlWorker.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (worker.isRunning()) {
            worker.stop();
        }
        Logger logger = (Logger) LoggerFactory.getLogger(CrawlWorker.class);
        logger.detachAppender(logAppender);
    }

    @Test
    void stopsBeforeInfrastructureBeans() {
        assertEquals(Integer.MAX_VALUE, worker.getPhase());
    }

    @Test
    void stopDoesNotSpamErrorsWhenQueueThrows() throws Exception {
        when(queueService.pop()).thenThrow(new RuntimeException("Redis connection closed"));

        worker.start();
        Thread.sleep(80);
        worker.stop();

        long errorsAtStop = errorCount();
        int popsAtStop = countPopInvocations();

        Thread.sleep(120);

        assertEquals(errorsAtStop, errorCount(), "error logs continued after stop");
        assertEquals(popsAtStop, countPopInvocations(), "queue pop continued after stop");
        assertTrue(errorsAtStop <= 4, "tight-loop error spam, count=" + errorsAtStop);
        assertFalse(worker.isRunning());
    }

    @Test
    void queueFailureBacksOffInsteadOfTightLoop() throws Exception {
        AtomicInteger pops = new AtomicInteger();
        when(queueService.pop()).thenAnswer(invocation -> {
            pops.incrementAndGet();
            throw new RuntimeException("Redis connection closed");
        });

        worker.start();
        Thread.sleep(150);
        worker.stop();

        assertTrue(pops.get() <= 8, "tight loop on Redis failure, pops=" + pops.get());
        verify(queueService, atMost(8)).pop();
    }

    @Test
    void emptyQueueStopDoesNotLogErrors() throws Exception {
        when(queueService.pop()).thenReturn(Optional.empty());

        worker.start();
        Thread.sleep(50);
        worker.stop();

        assertEquals(0, errorCount());
        assertFalse(worker.isRunning());
    }

    private long errorCount() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
    }

    private int countPopInvocations() {
        return org.mockito.Mockito.mockingDetails(queueService)
                .getInvocations()
                .size();
    }
}
