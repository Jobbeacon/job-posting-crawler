package org.example.jobpostingcrawler.structurediscovery.page;

import org.example.jobpostingcrawler.utils.UrlUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks job detail URLs from a listing/seed page.
 *
 * <p>The configured job-page-url-prefix alone is not enough (VietnamWorks' prefix is the site root), so links
 * are also required to end with an id-like number (job ids: {@code /2344854.html}, {@code -2115087-jv},
 * {@code -0143}) and are grouped by URL "shape"; the most frequent shape is assumed to be the job detail page.
 */
public final class SampleUrlCollector {

    private static final Pattern ID_RUN = Pattern.compile("\\d{4,}");
    private static final Pattern TOKEN = Pattern.compile("([^-_.]+)([-_.]?)");

    private SampleUrlCollector() {
    }

    public static List<String> collect(Document seed, String jobPageUrlPrefix, int limit) {
        return groups(seed, jobPageUrlPrefix).values().stream().findFirst()
                .map(list -> spread(list, limit))
                .orElse(List.of());
    }

    /**
     * Candidate job detail URLs grouped by URL shape (key), biggest group first. The biggest group is usually the job
     * pages, but not always (a homepage can link more company pages than jobs), so callers may try the next ones.
     */
    public static Map<String, List<String>> groups(Document seed, String jobPageUrlPrefix) {
        if (seed == null) {
            return Map.of();
        }
        String prefix = jobPageUrlPrefix == null ? "" : UrlUtils.stripWww(jobPageUrlPrefix);
        String seedUrl = UrlUtils.stripWww(stripQuery(seed.location()));

        Set<String> urls = new LinkedHashSet<>();
        for (Element a : seed.select("a[href]")) {
            String url = stripQuery(a.absUrl("href"));
            if (url.isEmpty() || !url.startsWith("http")) {
                continue;
            }
            String clean = UrlUtils.stripWww(url);
            if (!clean.startsWith(prefix) || clean.equals(prefix) || clean.equals(seedUrl)) {
                continue;
            }
            urls.add(url);
        }

        Map<String, List<String>> byShape = new LinkedHashMap<>();
        for (String url : urls) {
            String shape = shape(url);
            if (shape != null) {
                byShape.computeIfAbsent(shape, k -> new ArrayList<>()).add(url);
            }
        }
        return bySize(byShape);
    }

    private static final Pattern LISTING_WORDS = Pattern.compile(
            "viec-lam|tim-viec|tuyen-dung|jobs?|search|career", Pattern.CASE_INSENSITIVE);

    /**
     * Pages of the seed site likely to list jobs: same host, no id in the URL (category/search pages), those
     * whose path has a job word (/tim-viec-lam, /it-jobs, /viec-lam-ke-toan) first.
     */
    public static List<String> listingPages(Document seed, int limit) {
        if (seed == null) {
            return List.of();
        }
        String host = UrlUtils.extractDomain(seed.location());
        String seedUrl = UrlUtils.stripWww(stripQuery(seed.location())).replaceAll("/+$", "");
        Set<String> urls = new LinkedHashSet<>();
        for (Element a : seed.select("a[href]")) {
            String url = stripQuery(a.absUrl("href"));
            if (!url.startsWith("http") || host == null || !host.equals(UrlUtils.extractDomain(url))) {
                continue;
            }
            String path = path(url);
            if (path == null || path.replace("/", "").isEmpty()
                    || UrlUtils.stripWww(url).replaceAll("/+$", "").equals(seedUrl) || shape(url) != null) {
                continue;
            }
            urls.add(url);
        }
        return urls.stream()
                .sorted(Comparator.comparingInt(u -> LISTING_WORDS.matcher(Objects.requireNonNullElse(path(u), ""))
                        .find() ? 0 : 1))
                .limit(limit)
                .toList();
    }

    private static String path(String url) {
        try {
            return URI.create(url).getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Same map ordered by group size, biggest first (stable for equal sizes). */
    public static Map<String, List<String>> bySize(Map<String, List<String>> groups) {
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        groups.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> e.getValue().size()).reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** Takes samples spread over the list rather than the first N (often a single "hot jobs" box). */
    public static List<String> spread(List<String> list, int limit) {
        if (list.size() <= limit) {
            return list;
        }
        List<String> result = new ArrayList<>();
        double step = (double) list.size() / limit;
        for (int i = 0; i < limit; i++) {
            result.add(list.get((int) (i * step)));
        }
        return result;
    }

    /**
     * URL "shape": parent segments (ids replaced by '#') and the last token of the last segment ('#' when it
     * is an id, otherwise the suffix such as "html" or "jv"). Slug words are ignored since they vary per job.
     * <pre>
     *  /viec-lam/java-dev/2344854.html                            -> viec-lam/w|html
     *  /java-dev-1790924584127918890-2115086-jv                    -> |jv
     *  /vi/tim-viec-lam/pho-giam-doc-2026td457951.35C8DCE6.html    -> vi/tim-viec-lam|html
     *  /viec-lam/chuyen-vien-ads-BqP4oZkYqz                       -> viec-lam|#
     * </pre>
     * Returns null when the last segment carries no id-like token (category/search pages).
     */
    static String shape(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return null;
            }
            String[] segments = path.replaceAll("^/+|/+$", "").split("/");
            String last = segments[segments.length - 1];
            boolean hasId = false;
            String lastToken = "";
            Matcher m = TOKEN.matcher(last);
            while (m.find()) {
                String token = m.group(1);
                boolean id = isIdLike(token);
                hasId |= id;
                lastToken = id ? "#" : token.toLowerCase(Locale.ROOT);
            }
            if (!hasId) {
                return null;
            }
            List<String> parents = new ArrayList<>();
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i];
                if (i == 0) {
                    // section name ("viec-lam", "cong-ty", "detail-jobs"): tells job pages from other pages
                    parents.add(isIdLike(segment) && !segment.contains("-") ? "#" : segment);
                } else if (segment.contains("-") || segment.contains("_")) {
                    // a slug (topcv "/viec-lam/{slug}/{id}.html") varies per job, digits included
                    // ("...-thu-nhap-14-28-trieu"): never an id
                    parents.add("w");
                } else if (isIdLike(segment)) {
                    parents.add("#");
                } else {
                    parents.add(i > 0 ? "w" : segment);
                }
            }
            return String.join("/", parents) + "|" + lastToken;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Job ids: a 4+ digit run, a 6+ char token mixing letters and digits (hashes: 35C8DD20), or a 6+ char
     * random mixed-case token (123job: BDnBzQJvDR). Slug words are lowercase, so they never qualify.
     */
    static boolean isIdLike(String token) {
        if (ID_RUN.matcher(token).find()) {
            return true;
        }
        if (token.length() < 6) {
            return false;
        }
        boolean hasLetter = token.chars().anyMatch(Character::isLetter);
        if (hasLetter && token.chars().anyMatch(Character::isDigit)) {
            return true;
        }
        long upper = token.chars().filter(Character::isUpperCase).count();
        long lower = token.chars().filter(Character::isLowerCase).count();
        return upper >= 2 && lower >= 2;
    }

    private static String stripQuery(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.length();
        for (char c : new char[]{'?', '#'}) {
            int idx = url.indexOf(c);
            if (idx >= 0) {
                cut = Math.min(cut, idx);
            }
        }
        return url.substring(0, cut);
    }
}
