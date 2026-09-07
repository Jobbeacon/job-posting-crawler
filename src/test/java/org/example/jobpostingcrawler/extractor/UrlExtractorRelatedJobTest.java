package org.example.jobpostingcrawler.extractor;

import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.fetcher.Fetcher;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Test thủ công: điền URL job detail vào {@link #JOB_DETAIL_URL} rồi chạy class này.
 *
 * <pre>
 * mvn -Dtest=UrlExtractorRelatedJobTest test
 * </pre>
 *
 * Ví dụ:
 * https://www.topcv.vn/viec-lam/junior-fullstack-developer-nestjs-reactjs/2246269.html
 * https://itviec.com/it-jobs/devsecops-engineer-khoi-cong-nghe-thong-tin-mb-bank-2830
 */
class UrlExtractorRelatedJobTest {

    // Điền URL trang chi tiết job vào đây.
    private static final String JOB_DETAIL_URL = "https://itviec.com/it-jobs/devsecops-engineer-khoi-cong-nghe-thong-tin-mb-bank-2830";

    private static final Pattern TOPCV_JOB_DETAIL =
            Pattern.compile("^/viec-lam/.+/\\d+\\.html$");
    private static final Pattern ITVIEC_JOB_DETAIL =
            Pattern.compile("^/it-jobs/.+-\\d+/?$");

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void extractRelatedJobsFromJobDetailPage() {
        assumeFalse(
                JOB_DETAIL_URL == null || JOB_DETAIL_URL.isBlank(),
                "Điền JOB_DETAIL_URL (link job detail) rồi chạy lại test"
        );

        UrlExtractor extractor = new UrlExtractor(properties());
        Document document = new Fetcher().getDocument(JOB_DETAIL_URL.trim());
        List<String> extracted = extractor.extractUrls(document);
        List<String> relatedJobs = extracted.stream()
                .filter(UrlExtractorRelatedJobTest::isJobDetailUrl)
                .toList();

        System.out.println("Job detail: " + JOB_DETAIL_URL.trim());
        System.out.println("Related jobs (" + relatedJobs.size() + "):");
        relatedJobs.forEach(url -> System.out.println("  - " + url));
        if (relatedJobs.size() != extracted.size()) {
            System.out.println("Other crawlable urls:");
            extracted.stream()
                    .filter(url -> !relatedJobs.contains(url))
                    .forEach(url -> System.out.println("  - " + url));
        }

        assertFalse(
                relatedJobs.isEmpty(),
                () -> "Không tìm thấy job liên quan từ " + JOB_DETAIL_URL
                        + ". Extracted=" + extracted
        );
        relatedJobs.forEach(url -> {
            assertTrue(isJobDetailUrl(url), "Không phải job detail: " + url);
            assertFalse(
                    sameJob(JOB_DETAIL_URL.trim(), url),
                    "Vẫn trả về chính job đang xem: " + url
            );
        });
    }

    private static boolean isJobDetailUrl(String url) {
        String path = URI.create(url).getPath();
        return TOPCV_JOB_DETAIL.matcher(path).matches()
                || ITVIEC_JOB_DETAIL.matcher(path).matches();
    }

    private static boolean sameJob(String left, String right) {
        return stripQuery(left).equalsIgnoreCase(stripQuery(right));
    }

    private static String stripQuery(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/content")) {
            path = path.substring(0, path.length() - "/content".length());
        }
        return uri.getHost() + path;
    }

    private static CrawlerProperties properties() {
        return new CrawlerProperties(
                null,
                null,
                null,
                null,
                List.of(
                        new CrawlerProperties.SourceProperties(
                                "TOPCV", true, List.of("https://www.topcv.vn/viec-lam"), null, null
                        ),
                        new CrawlerProperties.SourceProperties(
                                "ITVIEC", true, List.of("https://itviec.com/it-jobs"), null, null
                        )
                )
        );
    }
}
