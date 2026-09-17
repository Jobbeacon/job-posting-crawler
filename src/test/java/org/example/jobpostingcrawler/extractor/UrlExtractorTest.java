package org.example.jobpostingcrawler.extractor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlExtractorTest {

    private static final String TOPCV_LISTING = "https://www.topcv.vn/viec-lam";
    private static final String ITVIEC_LISTING = "https://itviec.com/it-jobs";

    private UrlExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new UrlExtractor();
    }

    @Test
    void nullDocumentReturnsEmptyList() {
        assertTrue(extractor.extractUrls(null).isEmpty());
    }

    @Test
    void resolvesRelativeUrl() {
        Document document = html(
                TOPCV_LISTING,
                "<a href=\"/viec-lam/junior-fullstack-developer/2246269.html\">Job</a>"
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/junior-fullstack-developer/2246269.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void stripsQueryAndFragment() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html?ta_source=Box#section">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void rejectsMailtoAndJavascript() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="mailto:hr@topcv.vn">mail</a>
                <a href="javascript:void(0)">js</a>
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void deduplicatesUrls() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="/viec-lam/java-dev/111.html">relative</a>
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html?ta_source=list">absolute</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void excludesSelfLinkOfCurrentPage() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="https://www.topcv.vn/viec-lam">self</a>
                <a href="https://www.topcv.vn/viec-lam?page=1">self with stripped query</a>
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void stripsContentSuffix() {
        Document document = html(
                ITVIEC_LISTING,
                "<a href=\"https://itviec.com/it-jobs/java-developer-123/content\">job</a>"
        );

        assertEquals(
                List.of("https://itviec.com/it-jobs/java-developer-123"),
                extractor.extractUrls(document)
        );
    }

    private static Document html(String baseUri, String body) {
        return Jsoup.parse("<html><body>" + body + "</body></html>", baseUri);
    }
}
