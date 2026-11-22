package org.example.jobpostingcrawler.extractor;

import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.extractor.html.HtmlJobPostingExtractor;
import org.example.jobpostingcrawler.model.ExtractorConfig;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HtmlJobPostingExtractorTest {

    private static final String URL = "https://itviec.com/it-jobs/java-dev-123";

    private static HtmlJobPostingExtractor extractor(Map<String, String> xpaths) {
        DomainConfigProvider provider = mock(DomainConfigProvider.class);
        when(provider.activeConfig("itviec_com")).thenReturn(Optional.of(ExtractorConfig.fromMap(xpaths)));
        return new HtmlJobPostingExtractor(provider);
    }

    @Test
    void listFieldsAreSplitByChildTagsOfTheMatchedContainer() {
        Document doc = Jsoup.parse("""
                <h1>Java Dev</h1>
                <div>Skills:</div><div class="tags"><a>Java</a><a>Spring Boot</a><a> SQL </a></div>
                <div>Job Domain:</div><div class="domains"><span>Banking</span><span>Fintech</span></div>
                """, URL);
        Map<String, String> config = new HashMap<>();
        config.put("title", "//h1");
        config.put("skills", "//div[normalize-space(.)='Skills:']/following-sibling::div[1]");
        config.put("industry", "//div[normalize-space(.)='Job Domain:']/following-sibling::div[1]");

        JobPosting job = extractor(config).extract(doc, URL);

        assertEquals(List.of("Java", "Spring Boot", "SQL"), job.getSkills());
        // backend (CategoryClassificationServiceImpl) splits industry on ","
        assertEquals("Banking, Fintech", job.getIndustry());
    }

    @Test
    void plainTextListWithoutChildTagsIsSplitOnCommas() {
        // VietnamWorks: <label>KỸ NĂNG</label><p>QA, Training, Coaching</p>
        Document doc = Jsoup.parse("<h1>Java Dev</h1><div class=\"tags\">Java, Spring , </div>", URL);
        Map<String, String> config = new HashMap<>();
        config.put("title", "//h1");
        config.put("skills", "//div[@class='tags']");

        JobPosting job = extractor(config).extract(doc, URL);

        assertEquals(List.of("Java", "Spring"), job.getSkills());
        assertNull(job.getIndustry());
    }

    @Test
    void listFieldsAreNullWhenContainerIsEmpty() {
        Document doc = Jsoup.parse("<h1>Java Dev</h1><div class=\"tags\"> </div>", URL);
        Map<String, String> config = new HashMap<>();
        config.put("title", "//h1");
        config.put("skills", "//div[@class='tags']");

        assertNull(extractor(config).extract(doc, URL).getSkills());
    }

    @Test
    void typedFieldsAreParsedFromPageTexts() {
        // TopCV-like header box
        Document doc = Jsoup.parse("""
                <h1>Cán Bộ QS</h1>
                <span class="salary">20 - 35 triệu</span>
                <div class="item"><div>Kinh nghiệm</div><div>1 năm</div></div>
                <div class="item"><div>Hạn ứng tuyển</div><div>29/11/2026</div></div>
                <div class="item"><div>Ngày đăng</div><div>13/11/2026</div></div>
                """, URL);
        Map<String, String> config = new HashMap<>();
        config.put("title", "//h1");
        config.put("salary", "//span[@class='salary']");
        config.put("experienceMonths", "//div[normalize-space(.)='Kinh nghiệm']/following-sibling::div[1]");
        config.put("expiredDate", "//div[normalize-space(.)='Hạn ứng tuyển']/following-sibling::div[1]");
        config.put("postedDate", "//div[normalize-space(.)='Ngày đăng']/following-sibling::div[1]");

        JobPosting job = extractor(config).extract(doc, URL);

        assertEquals(20_000_000L, job.getMinSalary());
        assertEquals(35_000_000L, job.getMaxSalary());
        assertEquals("VND", job.getCurrency());
        assertEquals(12, job.getExperienceMonths());
        assertEquals(LocalDateTime.of(2026, 11, 29, 23, 59, 59), job.getExpiredDate());
        assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), job.getPostedDate());
    }

    @Test
    void typedFieldsStayEmptyWhenNotConfiguredOrUnparseable() {
        Document doc = Jsoup.parse("<h1>Dev</h1><span class=\"salary\">Thỏa thuận</span>", URL);
        Map<String, String> config = new HashMap<>();
        config.put("title", "//h1");
        config.put("salary", "//span[@class='salary']");

        JobPosting job = extractor(config).extract(doc, URL);

        assertNull(job.getMinSalary());
        assertNull(job.getMaxSalary());
        assertNull(job.getCurrency());
        assertEquals(0, job.getExperienceMonths());
        assertNull(job.getExpiredDate());
        assertNull(job.getPostedDate());
    }

    @Test
    void multipleTextMatchesAreJoinedWithPipe() {
        Document doc = Jsoup.parse("<h1>Java Dev</h1><span class=\"loc\">Hà Nội</span><span class=\"loc\">Hồ Chí Minh</span>", URL);
        Map<String, String> config = new HashMap<>();
        config.put("title", "//h1");
        config.put("location", "//span[@class='loc']");

        JobPosting job = extractor(config).extract(doc, URL);

        // backend (JobServiceImpl) splits location on "|"
        assertEquals("Hà Nội|Hồ Chí Minh", job.getLocation());
    }

    @Test
    void returnsNullWhenTheDomainHasNoActiveConfiguration() {
        DomainConfigProvider provider = mock(DomainConfigProvider.class);
        when(provider.activeConfig("itviec_com")).thenReturn(Optional.empty());

        assertNull(new HtmlJobPostingExtractor(provider).extract(Jsoup.parse("<h1>Dev</h1>", URL), URL));
    }
}
