package org.example.jobpostingcrawler.extractor;

import org.example.jobpostingcrawler.config.CrawlerProperties;
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
        extractor = new UrlExtractor(properties(
                source("TOPCV", true, TOPCV_LISTING),
                source("ITVIEC", true, ITVIEC_LISTING)
        ));
    }

    @Test
    void nullDocumentReturnsEmptyList() {
        assertTrue(extractor.extractUrls(null).isEmpty());
    }

    @Test
    void resolvesRelativeTopCvJobUrl() {
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
    void stripsTrackingQueryAndKeepsPagination() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html?ta_source=Box&u_sr_id=abc&utm_source=x">job</a>
                <a href="https://www.topcv.vn/viec-lam?page=2&ta_source=nav">next</a>
                """
        );

        assertEquals(
                List.of(
                        "https://www.topcv.vn/viec-lam/java-dev/111.html",
                        "https://www.topcv.vn/viec-lam?page=2"
                ),
                extractor.extractUrls(document)
        );
    }

    @Test
    void stripsFragment() {
        Document document = html(
                TOPCV_LISTING,
                "<a href=\"https://www.topcv.vn/viec-lam/java-dev/111.html#section\">job</a>"
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void rejectsMailtoJavascriptAndOffsiteLinks() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="mailto:hr@topcv.vn">mail</a>
                <a href="javascript:void(0)">js</a>
                <a href="https://facebook.com/topcv">social</a>
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void deduplicatesRelativeAndAbsoluteUrls() {
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
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void extractsItviecJobUrlFromDataAttribute() {
        Document document = html(
                ITVIEC_LISTING,
                """
                <div data-search--job-selection-job-url-value="/it-jobs/java-developer-123"></div>
                """
        );

        assertEquals(
                List.of("https://itviec.com/it-jobs/java-developer-123"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void stripsItviecContentSuffix() {
        Document document = html(
                ITVIEC_LISTING,
                "<a href=\"https://itviec.com/it-jobs/java-developer-123/content\">job</a>"
        );

        assertEquals(
                List.of("https://itviec.com/it-jobs/java-developer-123"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void rejectsLoginApplyAndCvPathsOnSameHost() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="https://www.topcv.vn/viec-lam/ung-tuyen/111">apply</a>
                <a href="https://www.topcv.vn/login">login</a>
                <a href="https://www.topcv.vn/cv-hay">cv</a>
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void ignoresDisabledSources() {
        extractor = new UrlExtractor(properties(
                source("TOPCV", false, TOPCV_LISTING),
                source("ITVIEC", true, ITVIEC_LISTING)
        ));

        Document document = html(
                TOPCV_LISTING,
                """
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">topcv</a>
                <a href="https://itviec.com/it-jobs/java-developer-123">itviec</a>
                """
        );

        assertEquals(
                List.of("https://itviec.com/it-jobs/java-developer-123"),
                extractor.extractUrls(document)
        );
    }

    @Test
    void doesNotMatchLongerPathThatOnlySharesPrefixChars() {
        Document document = html(
                TOPCV_LISTING,
                """
                <a href="https://www.topcv.vn/viec-lam-it">other listing</a>
                <a href="https://www.topcv.vn/viec-lam/java-dev/111.html">job</a>
                """
        );

        assertEquals(
                List.of("https://www.topcv.vn/viec-lam/java-dev/111.html"),
                extractor.extractUrls(document)
        );
    }

    private static Document html(String baseUri, String body) {
        return Jsoup.parse("<html><body>" + body + "</body></html>", baseUri);
    }

    private static CrawlerProperties properties(CrawlerProperties.SourceProperties... sources) {
        return new CrawlerProperties(null, null, null, null, List.of(sources));
    }

    private static CrawlerProperties.SourceProperties source(String name, boolean enabled, String seed) {
        return new CrawlerProperties.SourceProperties(name, enabled, List.of(seed));
    }
}
