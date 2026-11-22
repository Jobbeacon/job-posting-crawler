package org.example.jobpostingcrawler.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig.Status;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DomainConfigProviderTest {

    private final DomainExtractorConfigRepository repository = mock(DomainExtractorConfigRepository.class);
    private final DomainConfigProvider provider = new DomainConfigProvider(repository, new ObjectMapper());

    private static DomainExtractorConfig row(String key, Status status, int attempts, String xpaths) {
        return DomainExtractorConfig.builder().domainKey(key).domain(key.replace('_', '.'))
                .status(status).attempts(attempts).xpaths(xpaths).build();
    }

    @Test
    void domainKeyStripsWwwAndReplacesDots() {
        assertEquals("topcv_vn", DomainConfigProvider.domainKey("https://www.topcv.vn/viec-lam/abc/1.html"));
        assertEquals("itviec_com", DomainConfigProvider.domainKey("https://itviec.com/it-jobs"));
    }

    @Test
    void onlyActiveRowsGiveXpaths() {
        when(repository.findAll()).thenReturn(List.of(
                row("itviec_com", Status.ACTIVE, 0, "{\"title\": \"//h1\", \"skills\": \"//div[@class='tags']\"}"),
                row("topcv_vn", Status.FAILED, 2, null),
                row("vietnamworks_com", Status.RUNNING, 1, null)));
        provider.refresh();

        assertEquals("//h1", provider.activeConfig("itviec_com").orElseThrow().title());
        assertEquals("//div[@class='tags']", provider.activeConfig("itviec_com").orElseThrow().skills());
        assertTrue(provider.activeConfig("topcv_vn").isEmpty());
        assertTrue(provider.activeConfig("vietnamworks_com").isEmpty());
        assertTrue(provider.activeConfig("unknown_vn").isEmpty());
    }

    @Test
    void pausesOnlyDomainsWaitingForTheirFirstDiscovery() {
        when(repository.findAll()).thenReturn(List.of(
                row("itviec_com", Status.ACTIVE, 0, "{}"),
                row("a_vn", Status.RUNNING, 1, null),
                row("b_vn", Status.RUNNING, 2, null),
                row("c_vn", Status.FAILED, 1, null)));
        provider.refresh();

        // discovery off: never paused
        assertFalse(provider.isPaused("new_vn"));
        assertFalse(provider.isPaused("a_vn"));

        provider.markDiscoveryActive();
        assertTrue(provider.isPaused("new_vn"), "no row yet");
        assertTrue(provider.isPaused("a_vn"), "first attempt running");
        assertFalse(provider.isPaused("b_vn"), "retry: crawl with JSON-LD meanwhile");
        assertFalse(provider.isPaused("c_vn"));
        assertFalse(provider.isPaused("itviec_com"));
    }

    @Test
    void keepsPreviousEntriesAndNeverPausesWhenTheTableCannotBeRead() {
        when(repository.findAll()).thenThrow(new RuntimeException("DB down"));
        provider.markDiscoveryActive();
        provider.refresh();

        assertFalse(provider.isPaused("new_vn"));
        assertTrue(provider.activeConfig("itviec_com").isEmpty());
    }

    @Test
    void refreshPicksUpNewRows() {
        when(repository.findAll()).thenReturn(List.of(row("topcv_vn", Status.RUNNING, 1, null)));
        provider.refresh();
        assertTrue(provider.activeConfig("topcv_vn").isEmpty());

        when(repository.findAll()).thenReturn(List.of(row("topcv_vn", Status.ACTIVE, 1, "{\"title\": \"//h1\"}")));
        provider.refresh();
        assertEquals("//h1", provider.activeConfig("topcv_vn").orElseThrow().title());
    }

    @Test
    void invalidJsonGivesNoConfiguration() {
        when(repository.findAll()).thenReturn(List.of(row("topcv_vn", Status.ACTIVE, 1, "not json")));
        provider.refresh();
        assertTrue(provider.activeConfig("topcv_vn").isEmpty());
    }

    @Test
    void staleDomainKeepsItsWorkingXpaths() {
        DomainExtractorConfig stale = row("topcv_vn", Status.STALE, 1,
                "{\"title\": \"//h1\", \"location\": \"//div[@class='loc']\", \"salary\": \"//span\"}");
        stale.setStaleFields("[\"location\"]");
        stale.setFieldCoverage("{\"title\": 1.0, \"location\": 1, \"salary\": 0.67}");
        when(repository.findAll()).thenReturn(List.of(stale));
        provider.markDiscoveryActive();
        provider.refresh();

        var config = provider.activeConfig("topcv_vn").orElseThrow();
        assertEquals("//h1", config.title());
        assertEquals("//span", config.salary());
        assertEquals(null, config.location(), "broken XPath is not used");
        assertFalse(provider.isPaused("topcv_vn"));
        var state = provider.state("topcv_vn").orElseThrow();
        assertEquals(java.util.Set.of("location"), state.staleFields());
        assertEquals(0.67, state.fieldCoverage().get("salary"));
        assertEquals(1.0, state.fieldCoverage().get("location"));
    }

    @Test
    void rediscoveryDoesNotPauseNorDropTheXpaths() {
        // ITviec manual row (attempts 0) being discovered again after a layout change
        when(repository.findAll()).thenReturn(List.of(row("itviec_com", Status.RUNNING, 1, "{\"title\": \"//h1\"}")));
        provider.markDiscoveryActive();
        provider.refresh();

        assertFalse(provider.isPaused("itviec_com"));
        assertEquals("//h1", provider.activeConfig("itviec_com").orElseThrow().title());
    }
}
