package org.example.jobpostingcrawler.extractor.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig.Status;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider.DomainState;
import org.example.jobpostingcrawler.extractor.html.HtmlJobPostingExtractor.HtmlExtraction;
import org.example.jobpostingcrawler.model.ExtractorConfig;
import org.example.jobpostingcrawler.model.JobPosting;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionHealthMonitorTest {

    private static final String KEY = "topcv_vn";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final String DESCRIPTION = "Mô tả công việc: " + "chăm sóc khách hàng, ".repeat(10);

    private final DomainConfigProvider provider = mock(DomainConfigProvider.class);
    private final DomainExtractorConfigRepository repository = mock(DomainExtractorConfigRepository.class);
    // small numbers to keep the tests short: window 20 pages, decision after 10, evaluated every 5
    private final ExtractionHealthProperties properties =
            new ExtractionHealthProperties(true, 20, 10, 0.8, 0.2, 5, Duration.ofHours(24));
    private final ExtractionHealthMonitor monitor = new ExtractionHealthMonitor(provider, repository,
            new ObjectMapper(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
    private DomainExtractorConfig row;

    @BeforeEach
    void setUp() {
        row = DomainExtractorConfig.builder().domainKey(KEY).domain("topcv.vn").status(Status.ACTIVE)
                .xpaths("{}").startedAt(NOW.minus(Duration.ofDays(10))).updatedAt(NOW.minus(Duration.ofDays(10)))
                .build();
        when(repository.findById(KEY)).thenReturn(Optional.of(row));
        state(Map.of("title", 1.0, "description", 1.0, "location", 1.0, "salary", 1.0, "skills", 0.5), Set.of());
    }

    private void state(Map<String, Double> coverage, Set<String> stale) {
        when(provider.state(KEY)).thenReturn(Optional.of(new DomainState(Status.ACTIVE, 1, ExtractorConfig.fromMap(Map.of("title", "//h1")),
                coverage, stale, row.getUpdatedAt())));
    }

    private static HtmlExtraction page(String title, String location, String salary, List<String> skills) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("title", title);
        raw.put("companyName", "Công ty ABC");
        raw.put("description", DESCRIPTION);
        raw.put("location", location);
        raw.put("salary", salary);
        raw.put("skills", skills);
        return new HtmlExtraction(KEY, null, raw);
    }

    private static JobPosting jsonLd(String title) {
        return JobPosting.builder().title(title).companyName("CÔNG TY ABC").build();
    }

    private void feed(int pages, String title, String location, String salary, List<String> skills, String jsonTitle) {
        for (int i = 0; i < pages; i++) {
            monitor.record(page(title, location, salary, skills), jsonTitle == null ? null : jsonLd(jsonTitle));
        }
    }

    @Test
    void healthyPagesChangeNothing() {
        feed(40, "Kế Toán Tổng Hợp", "Hà Nội", "10 - 15 triệu", List.of("Excel"), "Kế toán tổng hợp - Hà Nội");
        verify(repository, never()).save(any());
    }

    @Test
    void brokenRequiredFieldMakesTheDomainStale() {
        feed(10, "Kế Toán", "Hà Nội", "10 - 15 triệu", List.of("Excel"), "Kế Toán");
        // new layout: the location XPath finds nothing any more
        feed(20, "Kế Toán", null, "10 - 15 triệu", List.of("Excel"), "Kế Toán");

        verify(repository).save(row);
        assertEquals(Status.STALE, row.getStatus());
        assertEquals("[\"location\"]", row.getStaleFields());
        assertEquals(NOW, row.getStaleSince());
        assertEquals(NOW, row.getNextRetryAt(), "last discovery was 10 days ago: run again now");
        assertTrue(row.getError().contains("location valid on"), row.getError());
        verify(provider).refresh();
    }

    @Test
    void oneBrokenOptionalFieldIsOnlyDisabled() {
        feed(10, "Kế Toán", "Hà Nội", "10 - 15 triệu", List.of("Excel"), "Kế Toán");
        feed(20, "Kế Toán", "Hà Nội", "Nhân viên", List.of("Excel"), "Kế Toán"); // salary XPath now reads the level

        assertEquals(Status.ACTIVE, row.getStatus());
        assertEquals("[\"salary\"]", row.getStaleFields());
        assertNull(row.getNextRetryAt());
    }

    @Test
    void titleThatNoLongerMatchesJsonLdIsBroken() {
        // still a valid looking title, but another element: the "related jobs" box
        feed(20, "Việc làm liên quan khác", "Hà Nội", "10 - 15 triệu", List.of("Excel"), "Kế Toán Tổng Hợp");

        assertEquals(Status.STALE, row.getStatus());
        assertTrue(row.getError().contains("title matches JSON-LD on 0%"), row.getError());
    }

    @Test
    void jsonLdWordingDifferencesAreNotABreak() {
        feed(30, "KẾ TOÁN TỔNG HỢP (THU NHẬP 15 TRIỆU)", "Hà Nội", "10 - 15 triệu", List.of("Excel"),
                "Kế toán tổng hợp");
        verify(repository, never()).save(any());
    }

    @Test
    void fieldsRarelyFoundAtDiscoveryAreNotWatched() {
        // skills were on 50% of the samples: absent skills say nothing about the layout
        feed(30, "Kế Toán", "Hà Nội", "10 - 15 triệu", null, "Kế Toán");
        verify(repository, never()).save(any());
    }

    @Test
    void blockedPagesAreNotCounted() {
        // captcha page: no JSON-LD and nothing valid
        for (int i = 0; i < 30; i++) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("title", null);
            raw.put("location", null);
            raw.put("description", "Please verify");
            monitor.record(new HtmlExtraction(KEY, null, raw), null);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void atMostOneDiscoveryADay() {
        row.setStartedAt(NOW.minus(Duration.ofHours(3)));
        feed(20, "Kế Toán", null, "10 - 15 triệu", List.of("Excel"), "Kế Toán");

        assertEquals(Status.STALE, row.getStatus());
        assertEquals(NOW.plus(Duration.ofHours(21)), row.getNextRetryAt());
    }

    @Test
    void manualRowsWithoutBaselineWatchRequiredFieldsOnly() {
        state(Map.of(), Set.of());
        feed(20, "Kế Toán", "Hà Nội", null, List.of("Excel"), "Kế Toán"); // salary always missing: not watched
        verify(repository, never()).save(any());

        feed(20, "Kế Toán", null, null, List.of("Excel"), "Kế Toán");
        assertEquals(Status.STALE, row.getStatus());
    }

    @Test
    void inactiveDomainsAreIgnored() {
        when(provider.state(KEY)).thenReturn(Optional.of(new DomainState(Status.RUNNING, 2,
                ExtractorConfig.fromMap(Map.of("title", "//h1")), Map.of(), Set.of(), NOW)));
        feed(30, "Kế Toán", null, null, null, "Kế Toán");
        verify(repository, never()).save(any());
    }
}
