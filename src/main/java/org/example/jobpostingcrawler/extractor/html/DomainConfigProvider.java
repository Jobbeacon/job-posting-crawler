package org.example.jobpostingcrawler.extractor.html;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig.Status;
import org.example.jobpostingcrawler.model.ExtractorConfig;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.example.jobpostingcrawler.utils.UrlUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory view of the domain_extractor_config table (a handful of rows), refreshed periodically and right
 * after a discovery run, so the crawl path never queries the DB per page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainConfigProvider {

    private static final TypeReference<Map<String, String>> XPATH_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Double>> COVERAGE_MAP = new TypeReference<>() {
    };

    private final DomainExtractorConfigRepository repository;
    private final ObjectMapper objectMapper;

    private volatile Map<String, DomainState> states = Map.of();
    private volatile boolean loaded;
    private volatile boolean discoveryActive;

    /**
     * @param config        XPaths to use (stale fields removed), null when the domain has none
     * @param fieldCoverage share of the discovery samples where each field was found (empty for manual rows)
     * @param staleFields   fields whose XPath stopped working
     * @param version       changes whenever the row is written: health statistics restart from zero
     */
    public record DomainState(Status status, int attempts, ExtractorConfig config, Map<String, Double> fieldCoverage,
                              Set<String> staleFields, Instant version) {
    }

    /** domain key of a URL: host without "www.", dots replaced by underscores (topcv.vn -> topcv_vn). */
    public static String domainKey(String url) {
        String domain = UrlUtils.extractDomain(url);
        return domain == null ? null : domain.replace(".", "_");
    }

    @PostConstruct
    void init() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${crawler.extractor.config-refresh-interval:5m}",
            initialDelayString = "${crawler.extractor.config-refresh-interval:5m}")
    public void refresh() {
        try {
            Map<String, DomainState> next = new HashMap<>();
            for (DomainExtractorConfig row : repository.findAll()) {
                next.put(row.getDomainKey(), toState(row));
            }
            states = Map.copyOf(next);
            loaded = true;
            log.debug("Loaded extractor config of {} domains", next.size());
        } catch (Exception e) {
            log.warn("Cannot load domain_extractor_config, keeping the previous {} entries: {}",
                    states.size(), e.getMessage());
        }
    }

    /** XPaths to use for this domain (stale fields removed), empty when it has none. */
    public Optional<ExtractorConfig> activeConfig(String domainKey) {
        DomainState state = domainKey == null ? null : states.get(domainKey);
        return state == null ? Optional.empty() : Optional.ofNullable(state.config());
    }

    public Optional<DomainState> state(String domainKey) {
        return domainKey == null ? Optional.empty() : Optional.ofNullable(states.get(domainKey));
    }

    /**
     * The crawler waits for the first discovery run of a domain: no row yet, or RUNNING its first attempt without
     * any XPath. Retries after a failure, and re-discoveries of a STALE domain, do not pause it. Never pauses when
     * the discovery module is off or the table could not be read.
     */
    public boolean isPaused(String domainKey) {
        if (!discoveryActive || !loaded || domainKey == null) {
            return false;
        }
        DomainState state = states.get(domainKey);
        return state == null
                || (state.status() == Status.RUNNING && state.attempts() <= 1 && state.config() == null);
    }

    /** Called by the discovery coordinator when it is running (enabled and with an API key). */
    public void markDiscoveryActive() {
        discoveryActive = true;
    }

    /** ACTIVE, STALE and RUNNING (re-discovery) rows keep their XPaths, minus the stale fields. */
    private DomainState toState(DomainExtractorConfig row) {
        Set<String> stale = Set.copyOf(parseJson(row, row.getStaleFields(), STRING_LIST, List.of()));
        ExtractorConfig config = null;
        if (row.getStatus() != Status.FAILED) {
            Map<String, String> xpaths = new HashMap<>(parseXpaths(row));
            xpaths.keySet().removeAll(stale);
            if (!xpaths.isEmpty()) {
                config = ExtractorConfig.fromMap(xpaths);
            }
        }
        Map<String, Double> coverage = Map.copyOf(parseJson(row, row.getFieldCoverage(), COVERAGE_MAP, Map.of()));
        return new DomainState(row.getStatus(), row.getAttempts(), config, coverage, stale, row.getUpdatedAt());
    }

    private <T> T parseJson(DomainExtractorConfig row, String json, TypeReference<T> type, T empty) {
        if (json == null || json.isBlank()) {
            return empty;
        }
        try {
            T value = objectMapper.readValue(json, type);
            return value == null ? empty : value;
        } catch (Exception e) {
            log.warn("Invalid JSON for domain {}: {}", row.getDomainKey(), e.getMessage());
            return empty;
        }
    }

    private Map<String, String> parseXpaths(DomainExtractorConfig row) {
        if (row.getXpaths() == null || row.getXpaths().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(row.getXpaths(), XPATH_MAP);
        } catch (Exception e) {
            log.warn("Invalid xpaths JSON for domain {}: {}", row.getDomainKey(), e.getMessage());
            return Map.of();
        }
    }
}
