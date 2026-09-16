package org.example.jobpostingcrawler.extractor;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
public class UrlExtractor {

    public List<String> extractUrls(Document document) {
        if (document == null) {
            return List.of();
        }

        String current = normalize(document.baseUri());
        Set<String> urls = new LinkedHashSet<>();

        for (Element element : document.select("a[href]")) {
            String raw = element.attr("href");

            String resolved = resolve(document, raw);
            String normalized = normalize(resolved);

            if (normalized == null || normalized.equals(current)) {
                continue;
            }

            urls.add(normalized);
        }

        log.debug("extracted {} urls from {}", urls.size(), current);

        return List.copyOf(urls);
    }

    private String resolve(Document document, String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String baseUri = document.baseUri();

        if (baseUri == null || baseUri.isBlank()) {
            return raw;
        }

        try {
            return URI.create(baseUri)
                    .resolve(raw.trim())
                    .toString();
        } catch (IllegalArgumentException exception) {
            log.debug("Failed to resolve url={}", raw);
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

        if (path.endsWith("/content")
                && path.length() > "/content".length()) {
            path = path.substring(
                    0,
                    path.length() - "/content".length()
            );
        }

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        int port = uri.getPort();

        try {
            return new URI(
                    scheme,
                    null,
                    host,
                    port,
                    path,
                    null,
                    null
            ).toString();
        } catch (URISyntaxException exception) {
            log.debug("Skip url that cannot be normalized={}", raw);
            return null;
        }
    }
}
