package org.example.jobpostingcrawler.extractor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlExtractor {

    private static final Set<String> TRACKING_QUERY_KEYS = Set.of(
            "ta_source",
            "u_sr_id",
            "lab_feature",
            "click_id",
            "fbclid",
            "gclid"
    );

    private static final List<String> EXCLUDED_PATH_SEGMENTS = List.of(
            "/ung-tuyen",
            "/apply",
            "/login",
            "/sign-in",
            "/signin",
            "/users",
            "/blog",
            "/cv-hay"
    );

    private static final List<String> JOB_URL_ATTRIBUTES = List.of(
            "href",
            "data-search--job-selection-job-url-value",
            "data-search--job-selection-job-url"
    );

    private final CrawlerProperties properties;

    public List<String> extractUrls(Document document) {
        if (document == null) {
            return List.of();
        }

        String current = normalize(document.baseUri());
        List<AllowedTarget> allowed = allowedTargets();
        LinkedHashSet<String> urls = new LinkedHashSet<>();

        for (Element element : document.select("a[href], [data-search--job-selection-job-url-value], [data-search--job-selection-job-url]")) {
            for (String raw : rawUrls(document, element)) {
                String url = normalize(raw);
                if (url == null || url.equals(current) || !isCrawlable(url, allowed)) {
                    continue;
                }
                urls.add(url);
            }
        }

        log.debug("extracted {} urls from {}", urls.size(), current);
        return List.copyOf(urls);
    }

    private List<String> rawUrls(Document document, Element element) {
        List<String> raws = new ArrayList<>();
        for (String attribute : JOB_URL_ATTRIBUTES) {
            String value = element.attr(attribute);
            if (value == null || value.isBlank()) {
                continue;
            }
            String absolute = element.absUrl(attribute);
            raws.add(absolute == null || absolute.isBlank() ? resolve(document, value) : absolute);
        }
        return raws;
    }

    private String resolve(Document document, String raw) {
        if (raw == null || raw.isBlank() || document.baseUri() == null || document.baseUri().isBlank()) {
            return raw;
        }
        try {
            return URI.create(document.baseUri()).resolve(raw.trim()).toString();
        } catch (IllegalArgumentException exception) {
            return raw;
        }
    }

    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException exception) {
            log.debug("Skip invalid url={}", raw);
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (path.endsWith("/content") && path.length() > "/content".length()) {
            path = path.substring(0, path.length() - "/content".length());
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String query = keepAllowedQuery(uri.getQuery());
        int port = uri.getPort();

        try {
            return new URI(scheme, null, host, port, path, query, null).toString();
        } catch (URISyntaxException exception) {
            log.debug("Skip url that cannot be normalized={}", raw);
            return null;
        }
    }

    private String keepAllowedQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = (separator >= 0 ? pair.substring(0, separator) : pair).toLowerCase(Locale.ROOT);
            if (key.startsWith("utm_") || TRACKING_QUERY_KEYS.contains(key)) {
                continue;
            }
            if ("page".equals(key)) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private boolean isCrawlable(String url, List<AllowedTarget> allowed) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        String host = stripWww(uri.getHost());
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        if (isExcludedPath(path)) {
            return false;
        }

        for (AllowedTarget target : allowed) {
            if (target.hostWithoutWww().equals(host) && pathMatches(path, target.pathPrefix())) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        for (String segment : EXCLUDED_PATH_SEGMENTS) {
            if (normalized.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean pathMatches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private List<AllowedTarget> allowedTargets() {
        if (properties.sources() == null) {
            return List.of();
        }

        List<AllowedTarget> targets = new ArrayList<>();
        for (CrawlerProperties.SourceProperties source : properties.sources()) {
            if (source == null || !source.enabled() || source.seeds() == null) {
                continue;
            }
            for (String seed : source.seeds()) {
                String normalized = normalize(seed);
                if (normalized == null) {
                    continue;
                }
                URI uri = URI.create(normalized);
                String host = stripWww(uri.getHost());
                String path = uri.getPath();
                if (path == null || path.isBlank()) {
                    path = "/";
                }
                targets.add(new AllowedTarget(host, path));
            }
        }
        return targets;
    }

    private String stripWww(String host) {
        if (host == null) {
            return "";
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private record AllowedTarget(String hostWithoutWww, String pathPrefix) {
    }
}
