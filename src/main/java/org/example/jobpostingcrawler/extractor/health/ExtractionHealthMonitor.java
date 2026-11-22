package org.example.jobpostingcrawler.extractor.health;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.example.jobpostingcrawler.entity.DomainExtractorConfig.Status;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider.DomainState;
import org.example.jobpostingcrawler.extractor.html.HtmlJobPostingExtractor.HtmlExtraction;
import org.example.jobpostingcrawler.model.JobPosting;
import org.example.jobpostingcrawler.repository.DomainExtractorConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects XPaths broken by a site layout change, from the pages the crawler fetches anyway (no extra request,
 * no LLM call).
 * <ol>
 *   <li>Each job page of an ACTIVE/STALE domain: every configured field is valid (value of the right shape, see
 *   {@link FieldValidator}) or not; title and company name are also compared with JSON-LD, loosely.</li>
 *   <li>Results of the last {@code window} pages are kept per domain, in memory.</li>
 *   <li>A field found on at least 80% of the discovery samples whose share of valid values falls below 20%
 *   (over at least 30 pages), or whose title/company matches JSON-LD on less than 20% of the pages, is broken:
 *   it is added to stale_fields and the HTML extractor stops using it.</li>
 *   <li>A broken required field (title, description, location), or two broken fields, makes the domain STALE:
 *   the discovery coordinator runs again (at most once a day), the crawler keeps going meanwhile.</li>
 * </ol>
 * Pages with neither JSON-LD nor a single valid field (blocked page, captcha, not a job) are not counted.
 */
@Slf4j
@Component
public class ExtractionHealthMonitor {

    private static final Set<String> REQUIRED = Set.of("title", "description", "location");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DomainConfigProvider configProvider;
    private final DomainExtractorConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final ExtractionHealthProperties properties;
    private final Clock clock;
    private final Map<String, DomainStats> stats = new ConcurrentHashMap<>();

    @Autowired
    public ExtractionHealthMonitor(DomainConfigProvider configProvider, DomainExtractorConfigRepository repository,
                                   ObjectMapper objectMapper, ExtractionHealthProperties properties) {
        this(configProvider, repository, objectMapper, properties, Clock.systemUTC());
    }

    ExtractionHealthMonitor(DomainConfigProvider configProvider, DomainExtractorConfigRepository repository,
                            ObjectMapper objectMapper, ExtractionHealthProperties properties, Clock clock) {
        this.configProvider = configProvider;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Broken fields found by an evaluation; {@code domainStale}: the whole domain needs a new discovery. */
    record Verdict(Map<String, String> brokenFields, boolean domainStale) {
    }

    /**
     * @param html   HTML extraction of a job page (null when the domain has no XPath)
     * @param jsonLd JSON-LD extraction of the same page (nullable)
     */
    public void record(HtmlExtraction html, JobPosting jsonLd) {
        if (!properties.enabled() || html == null || html.raw().isEmpty()) {
            return;
        }
        DomainState state = configProvider.state(html.domainKey()).orElse(null);
        if (state == null || (state.status() != Status.ACTIVE && state.status() != Status.STALE)) {
            return;
        }
        String company = text(html.raw().get("companyName"));
        Map<String, Boolean> valid = new LinkedHashMap<>();
        html.raw().forEach((field, value) -> valid.put(field, FieldValidator.isValid(field, value, company)));
        boolean jsonLdJob = jsonLd != null && jsonLd.getTitle() != null && !jsonLd.getTitle().isBlank();
        if (!jsonLdJob && !valid.containsValue(true)) {
            return; // blocked page, captcha, not a job posting: says nothing about the XPaths
        }
        // only title and company name: the other fields are formatted differently in JSON-LD and HTML
        Map<String, Boolean> agrees = new LinkedHashMap<>();
        if (jsonLd != null) {
            compare(agrees, "title", text(html.raw().get("title")), jsonLd.getTitle());
            compare(agrees, "companyName", company, jsonLd.getCompanyName());
        }

        DomainStats domainStats = stats.compute(html.domainKey(), (key, old) ->
                old == null || !Objects.equals(old.version, state.version())
                        ? new DomainStats(key, state.version(), properties.window()) : old);
        Verdict verdict;
        synchronized (domainStats) {
            domainStats.add(valid, agrees);
            if (domainStats.pages % properties.evaluateEvery() != 0) {
                return;
            }
            verdict = evaluate(state, domainStats);
        }
        if (!verdict.brokenFields().isEmpty()) {
            markStale(html.domainKey(), verdict);
        }
    }

    Verdict evaluate(DomainState state, DomainStats domainStats) {
        Map<String, String> broken = new LinkedHashMap<>();
        domainStats.valid.forEach((field, window) -> {
            if (window.size() < properties.minPages()) {
                return;
            }
            Double baseline = state.fieldCoverage().get(field);
            if (baseline == null && REQUIRED.contains(field)) {
                baseline = 1.0; // manual row without discovery figures: required fields are expected everywhere
            }
            if (baseline != null && baseline >= properties.healthyBaseline()
                    && window.rate() < properties.brokenBelow()) {
                broken.put(field, "%s valid on %.0f%% of the last %d pages (found on %.0f%% at discovery)"
                        .formatted(field, window.rate() * 100, window.size(), baseline * 100));
            }
        });
        domainStats.agrees.forEach((field, window) -> {
            if (window.size() >= properties.minPages() && window.rate() < properties.brokenBelow()) {
                broken.putIfAbsent(field, "%s matches JSON-LD on %.0f%% of the last %d pages"
                        .formatted(field, window.rate() * 100, window.size()));
            }
        });
        if (log.isDebugEnabled()) {
            StringBuilder rates = new StringBuilder();
            domainStats.valid.forEach((f, w) -> rates.append(' ').append(f).append('=')
                    .append(Math.round(w.rate() * 100)).append("%/").append(w.size()));
            domainStats.agrees.forEach((f, w) -> rates.append(' ').append(f).append("~jsonld=")
                    .append(Math.round(w.rate() * 100)).append("%/").append(w.size()));
            log.debug("[ExtractionHealth] {} {} pages={} valid:{}", domainStats.domainKey, state.status(), domainStats.pages, rates);
        }
        Set<String> allBroken = new LinkedHashSet<>(state.staleFields());
        allBroken.addAll(broken.keySet());
        boolean domainStale = broken.keySet().stream().anyMatch(REQUIRED::contains) || allBroken.size() >= 2;
        return new Verdict(broken, domainStale);
    }

    private void markStale(String domainKey, Verdict verdict) {
        try {
            DomainExtractorConfig row = repository.findById(domainKey).orElse(null);
            if (row == null || (row.getStatus() != Status.ACTIVE && row.getStatus() != Status.STALE)) {
                return;
            }
            Instant now = clock.instant();
            Set<String> staleFields = new TreeSet<>(parseList(row.getStaleFields()));
            staleFields.addAll(verdict.brokenFields().keySet());
            row.setStaleFields(objectMapper.writeValueAsString(staleFields));
            String reason = String.join("; ", verdict.brokenFields().values());
            if (verdict.domainStale() && row.getStatus() == Status.ACTIVE) {
                row.setStatus(Status.STALE);
                row.setStaleSince(now);
                // at most one discovery a day: wait if the last one started less than a day ago
                Instant earliest = row.getStartedAt() == null ? now
                        : row.getStartedAt().plus(properties.rediscoveryDelay());
                row.setNextRetryAt(earliest.isAfter(now) ? earliest : now);
            }
            row.setError("Layout change: " + reason);
            row.setUpdatedAt(now);
            repository.save(row);
            log.warn("[ExtractionHealth] {} {}: {} (stale fields {})", domainKey, row.getStatus(), reason,
                    staleFields);
        } catch (Exception e) {
            log.warn("[ExtractionHealth] cannot mark {} as stale: {}", domainKey, e.getMessage());
        } finally {
            stats.remove(domainKey);
            configProvider.refresh();
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void compare(Map<String, Boolean> agrees, String field, String html, String jsonLd) {
        if (html != null && !html.isBlank() && jsonLd != null && !jsonLd.isBlank()) {
            agrees.put(field, FieldValidator.similar(html, jsonLd));
        }
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    /** Last results of one domain, reset when its row changes (new XPaths, new stale fields). */
    static final class DomainStats {
        final String domainKey;
        final Instant version;
        final int capacity;
        final Map<String, Window> valid = new LinkedHashMap<>();
        final Map<String, Window> agrees = new LinkedHashMap<>();
        long pages;

        DomainStats(String domainKey, Instant version, int capacity) {
            this.domainKey = domainKey;
            this.version = version;
            this.capacity = capacity;
        }

        void add(Map<String, Boolean> validFields, Map<String, Boolean> agreeingFields) {
            pages++;
            validFields.forEach((f, ok) -> valid.computeIfAbsent(f, k -> new Window(capacity)).add(ok));
            agreeingFields.forEach((f, ok) -> agrees.computeIfAbsent(f, k -> new Window(capacity)).add(ok));
        }
    }

    /** Fixed-size ring of booleans. */
    static final class Window {
        private final boolean[] values;
        private int next;
        private int size;
        private int trueCount;

        Window(int capacity) {
            values = new boolean[capacity];
        }

        void add(boolean value) {
            if (size == values.length) {
                if (values[next]) {
                    trueCount--;
                }
            } else {
                size++;
            }
            values[next] = value;
            if (value) {
                trueCount++;
            }
            next = (next + 1) % values.length;
        }

        int size() {
            return size;
        }

        double rate() {
            return size == 0 ? 1.0 : (double) trueCount / size;
        }
    }
}
