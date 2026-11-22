package org.example.jobpostingcrawler.structurediscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig.Status;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.example.jobpostingcrawler.structurediscovery.page.PageLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructureDiscoveryCoordinatorTest {

    private static final String KEY = "topcv_vn";
    private static final String SEED = "https://www.topcv.vn/viec-lam";

    private final Map<String, DomainExtractorConfig> table = new HashMap<>();
    private final DomainExtractorConfigRepository repository = mock(DomainExtractorConfigRepository.class);
    private final DomainConfigProvider provider = mock(DomainConfigProvider.class);
    private final HtmlStructureDiscoveryService service = mock(HtmlStructureDiscoveryService.class);
    private final CrawlQueueService queue = mock(CrawlQueueService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
    private final AtomicInteger loadersCreated = new AtomicInteger();
    private StructureDiscoveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(table.get(i.<String>getArgument(0))));
        when(repository.save(any())).thenAnswer(i -> {
            DomainExtractorConfig row = i.getArgument(0);
            table.put(row.getDomainKey(), row);
            return row;
        });
        CrawlerProperties crawler = new CrawlerProperties(null, null, null, null, List.of(
                new CrawlerProperties.SourceProperties("TOPCV", true, List.of(SEED),
                        "https://www.topcv.vn/viec-lam/", Duration.ZERO),
                new CrawlerProperties.SourceProperties("OFF", false, List.of("https://off.vn"), null, null)));
        coordinator = new StructureDiscoveryCoordinator(crawler, StructureDiscoveryProperties.defaults("key"),
                repository, provider, service, queue,
                () -> {
                    loadersCreated.incrementAndGet();
                    return url -> null;
                },
                new ObjectMapper(), clock, Runnable::run);
    }

    private static DiscoveryResult result(boolean withTitle) {
        Map<JobField, DiscoveryResult.FieldResult> fields = new HashMap<>();
        if (withTitle) {
            fields.put(JobField.TITLE, new DiscoveryResult.FieldResult(JobField.TITLE, "//h1", "tag", 4, 2, 2, 4,
                    List.of("Java Dev"), List.of()));
        }
        return new DiscoveryResult("topcv.vn", KEY, List.of("https://www.topcv.vn/viec-lam/a/1.html"), fields,
                withTitle ? List.of() : List.of("Required field 'title' was not found"));
    }

    private void discoveryReturns(DiscoveryResult result) {
        doReturn(result).when(service).discover(any(PageLoader.class), anyList(), anyString(), isNull(), anyInt());
    }

    @Test
    void registersItselfSoTheCrawlerWaitsForTheFirstRun() {
        verify(provider).markDiscoveryActive();
    }

    @Test
    void newDomainBecomesActiveAndItsSeedsArePushedAgain() {
        discoveryReturns(result(true));

        coordinator.checkSources();

        DomainExtractorConfig row = table.get(KEY);
        assertEquals(Status.ACTIVE, row.getStatus());
        assertEquals("topcv.vn", row.getDomain());
        assertEquals(1, row.getAttempts());
        assertEquals("{\"title\":\"//h1\"}", row.getXpaths());
        assertTrue(row.getSampleUrls().contains("/viec-lam/a/1.html"));
        assertNotNull(row.getReport());
        assertNull(row.getError());
        assertEquals(1, loadersCreated.get());
        verify(queue).push(new CrawlUrl(SEED, 0));
        // RUNNING then final state are published to the crawler
        verify(provider, org.mockito.Mockito.times(2)).refresh();
        // the disabled source is ignored
        assertEquals(1, table.size());
    }

    @Test
    void activeDomainIsNotDiscoveredAgain() {
        table.put(KEY, DomainExtractorConfig.builder().domainKey(KEY).domain("topcv.vn").status(Status.ACTIVE).build());

        coordinator.checkSources();

        verify(service, never()).discover(any(), anyList(), anyString(), any(), anyInt());
        verify(queue, never()).push(any());
    }

    @Test
    void failureIsRetriedWithGrowingDelay() {
        doThrow(new IllegalStateException("Gemini 429"))
                .when(service).discover(any(PageLoader.class), anyList(), anyString(), isNull(), anyInt());

        coordinator.checkSources();
        DomainExtractorConfig row = table.get(KEY);
        assertEquals(Status.FAILED, row.getStatus());
        assertEquals("Gemini 429", row.getError());
        assertEquals(clock.instant().plus(Duration.ofHours(1)), row.getNextRetryAt());
        // seeds pushed anyway: the domain is no longer paused and is crawled with JSON-LD only
        verify(queue).push(new CrawlUrl(SEED, 0));

        clock.advance(Duration.ofMinutes(59));
        coordinator.checkSources();
        assertEquals(1, row.getAttempts(), "not due before next_retry_at");

        clock.advance(Duration.ofMinutes(1));
        coordinator.checkSources();
        assertEquals(2, row.getAttempts());
        assertEquals(clock.instant().plus(Duration.ofHours(2)), row.getNextRetryAt());

        discoveryReturns(result(true));
        clock.advance(Duration.ofHours(2));
        coordinator.checkSources();
        assertEquals(Status.ACTIVE, row.getStatus());
        assertEquals(3, row.getAttempts());
        assertNull(row.getError());
        assertNull(row.getNextRetryAt());
    }

    @Test
    void resultWithoutAnyFieldIsAFailure() {
        discoveryReturns(result(false));

        coordinator.checkSources();

        DomainExtractorConfig row = table.get(KEY);
        assertEquals(Status.FAILED, row.getStatus());
        assertTrue(row.getError().startsWith("No field could be located"), row.getError());
        assertNull(row.getXpaths());
    }

    @Test
    void browserStartFailureIsAFailure() {
        StructureDiscoveryCoordinator broken = new StructureDiscoveryCoordinator(
                new CrawlerProperties(null, null, null, null, List.of(new CrawlerProperties.SourceProperties(
                        "TOPCV", true, List.of(SEED), "https://www.topcv.vn/viec-lam/", null))),
                StructureDiscoveryProperties.defaults("key"), repository, provider, service, queue,
                () -> {
                    throw new IllegalStateException("Chromium missing");
                },
                new ObjectMapper(), clock, Runnable::run);

        broken.checkSources();

        assertEquals(Status.FAILED, table.get(KEY).getStatus());
        assertEquals("Chromium missing", table.get(KEY).getError());
    }

    @Test
    void runningRowIsOnlyRerunWhenStale() {
        discoveryReturns(result(true));
        table.put(KEY, DomainExtractorConfig.builder().domainKey(KEY).domain("topcv.vn").status(Status.RUNNING)
                .attempts(1).startedAt(clock.instant()).build());

        clock.advance(Duration.ofMinutes(29));
        coordinator.checkSources();
        assertEquals(Status.RUNNING, table.get(KEY).getStatus(), "another run is still in progress");

        clock.advance(Duration.ofMinutes(1));
        coordinator.checkSources();
        assertEquals(Status.ACTIVE, table.get(KEY).getStatus());
        assertEquals(2, table.get(KEY).getAttempts());
    }

    private DomainExtractorConfig staleRow() {
        return DomainExtractorConfig.builder().domainKey(KEY).domain("topcv.vn").status(Status.STALE).attempts(1)
                .xpaths("{\"title\":\"//h1\",\"location\":\"//div[@class='old-loc']\"}").staleFields("[\"location\"]")
                .staleSince(clock.instant()).nextRetryAt(clock.instant().plus(Duration.ofHours(5))).build();
    }

    @Test
    void staleDomainIsDiscoveredAgainWhenDue() {
        discoveryReturns(result(true));
        table.put(KEY, staleRow());

        coordinator.checkSources();
        assertEquals(Status.STALE, table.get(KEY).getStatus(), "not before next_retry_at");

        clock.advance(Duration.ofHours(5));
        coordinator.checkSources();
        DomainExtractorConfig row = table.get(KEY);
        assertEquals(Status.ACTIVE, row.getStatus());
        assertEquals("{\"title\":\"//h1\"}", row.getXpaths());
        assertNull(row.getStaleFields());
        assertNull(row.getStaleSince());
        assertEquals("{\"title\":1.0}", row.getFieldCoverage(), "baseline of the health monitor");
    }

    @Test
    void failedRediscoveryKeepsTheWorkingXpaths() {
        doThrow(new IllegalStateException("Gemini 429"))
                .when(service).discover(any(PageLoader.class), anyList(), anyString(), isNull(), anyInt());
        DomainExtractorConfig stale = staleRow();
        stale.setNextRetryAt(null);
        table.put(KEY, stale);

        coordinator.checkSources();

        DomainExtractorConfig row = table.get(KEY);
        assertEquals(Status.STALE, row.getStatus(), "not FAILED: title still works");
        assertTrue(row.getXpaths().contains("//h1"));
        assertEquals("[\"location\"]", row.getStaleFields());
        assertEquals(clock.instant().plus(Duration.ofHours(24)), row.getNextRetryAt());
    }

    @Test
    void throttledSamplePagesAreLoadedAgain() {
        AtomicInteger calls = new AtomicInteger();
        List<String> throttled = new java.util.ArrayList<>();
        PageLoader site = url -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("HTTP 429 for " + url);
            }
            return org.jsoup.Jsoup.parse("<h1>ok</h1>");
        };
        try (PageLoader loader = new StructureDiscoveryCoordinator.PacedPageLoader(site, Duration.ZERO,
                throttled::add, Duration.ofMillis(1))) {
            assertEquals("ok", loader.load("https://www.topcv.vn/viec-lam/a/1.html").text());
        }
        assertEquals(3, calls.get());
        assertEquals(3, throttled.size(), "every attempt goes through the crawler's domain schedule");
    }

    @Test
    void otherLoadErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        PageLoader site = url -> {
            calls.incrementAndGet();
            throw new IllegalStateException("HTTP 404 for " + url);
        };
        try (PageLoader loader = new StructureDiscoveryCoordinator.PacedPageLoader(site, Duration.ZERO, null,
                Duration.ofMillis(1))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> loader.load("u"));
        }
        assertEquals(1, calls.get());
    }

    @Test
    void retryDelayDoublesUpTo24Hours() {
        List<Long> hours = List.of(1, 2, 3, 4, 5, 6, 7, 20).stream()
                .map(a -> coordinator.retryDelay(a).toHours()).toList();
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 24L, 24L, 24L), hours);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
