package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.model.ExtractorConfig;
import org.example.jobpostingcrawler.structurediscovery.HtmlStructureDiscoveryService.SamplePage;
import org.example.jobpostingcrawler.structurediscovery.llm.LlmSelection;
import org.example.jobpostingcrawler.structurediscovery.llm.StructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.simplify.HtmlSimplifier;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathEvaluator;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlStructureDiscoveryServiceTest {

    /** Plays the LLM: picks nodes with CSS selectors, like a correct model would. */
    private static final StructureLlmClient FAKE_LLM = (page, url) -> {
        Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
        pick(page, picks, JobField.TITLE, "h1.job-title", null);
        pick(page, picks, JobField.COMPANY_NAME, "a.company-name", null);
        pick(page, picks, JobField.COMPANY_LOGO_URL, "img.logo", null);
        pick(page, picks, JobField.DESCRIPTION, "section.job-description", null);
        pick(page, picks, JobField.EXPERIENCE_MONTHS, "div.info-item:nth-child(1) .info-value", "Kinh nghiệm");
        pick(page, picks, JobField.EXPIRED_DATE, "div.info-item:nth-child(2) .info-value", "Hạn nộp hồ sơ");
        pick(page, picks, JobField.LOCATION, "div.info-item:nth-child(3) .info-value", "Địa điểm");
        pick(page, picks, JobField.SALARY, "span.salary", "");
        pick(page, picks, JobField.SKILLS, "div.skill-list", "Kỹ năng:");
        return new LlmSelection(true, picks, "fake", null, null);
    };

    private static void pick(SimplifiedPage page, Map<JobField, LlmSelection.FieldPick> picks, JobField field,
                             String css, String label) {
        Element e = page.document().selectFirst(css);
        if (e == null) {
            return;
        }
        Map<Element, Integer> ids = new IdentityHashMap<>();
        page.nodes().forEach((id, el) -> ids.put(el, id));
        Integer id = ids.get(e);
        if (id != null) {
            picks.put(field, new LlmSelection.FieldPick(id, label, null));
        }
    }

    private static List<SamplePage> samples() {
        return List.of(
                new SamplePage("https://jobs.example.com/viec-lam/1.html", StructureDiscoveryFixtures.jobPage(
                        "Java Developer", "Cty A", "2 năm", "30/12/2026", "15 - 20 triệu", "Hà Nội", true, "Java", "Spring")),
                new SamplePage("https://jobs.example.com/viec-lam/2.html", StructureDiscoveryFixtures.jobPage(
                        "Kế toán", "Cty B", "Không yêu cầu", "01/01/2027", "", "Hồ Chí Minh", false, "Excel")),
                new SamplePage("https://jobs.example.com/viec-lam/3.html", StructureDiscoveryFixtures.jobPage(
                        "Tester", "Cty C", "1 năm", "15/12/2026", "Thỏa thuận", "Đà Nẵng", true, "Selenium", "Jira")));
    }

    @Test
    void discoversRobustXpathsThatWorkOnEverySample() {
        HtmlStructureDiscoveryService service = new HtmlStructureDiscoveryService(FAKE_LLM, new HtmlSimplifier(), 2);
        List<SamplePage> samples = samples();
        DiscoveryResult result = service.discover("jobs.example.com", samples);

        assertEquals("jobs_example_com", result.domainKey());
        assertTrue(result.isComplete(), result.report());

        ExtractorConfig config = result.toExtractorConfig();
        assertEquals("//h1", config.title()); // first h1 of the page: simplest robust choice
        assertTrue(config.experienceMonths().contains("'Kinh nghiệm'"), config.experienceMonths());
        assertTrue(config.expiredDate().contains("'Hạn nộp hồ sơ'"), config.expiredDate());
        assertTrue(config.companyLogoUrl().endsWith("/@src"));
        assertFalse(config.location().matches(".*\\[\\d+]/.*\\[\\d+].*"), "positional xpath: " + config.location());

        // the chosen xpaths extract the right value on a page the "LLM" never saw (3rd sample)
        Document third = samples.get(2).document();
        assertEquals("Tester", XPathEvaluator.evaluate(third, config.title(), JobField.Kind.TEXT).value());
        assertEquals("1 năm", XPathEvaluator.evaluate(third, config.experienceMonths(), JobField.Kind.TEXT).value());
        assertEquals("15/12/2026", XPathEvaluator.evaluate(third, config.expiredDate(), JobField.Kind.TEXT).value());
        assertEquals("Đà Nẵng", XPathEvaluator.evaluate(third, config.location(), JobField.Kind.TEXT).value());
        assertEquals("Selenium, Jira", XPathEvaluator.evaluate(third, config.skills(), JobField.Kind.LIST).value());

        // salary is missing on sample 2: still found, with a coverage warning
        assertNotNull(config.salary());
        assertEquals(2, result.fields().get(JobField.SALARY).coverage());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("salary")));
        assertNull(config.industry());

        // stored as domain_extractor_config.xpaths, keyed like ExtractorConfig
        Map<String, String> xpaths = result.toXpathMap();
        assertEquals("//h1", xpaths.get("title"));
        assertEquals(config, ExtractorConfig.fromMap(xpaths));
        assertFalse(xpaths.containsKey("industry"));
    }

    @Test
    void joinsXpathsWithUnionWhenTheSiteServesTwoTemplates() {
        Document oldLayout = org.jsoup.Jsoup.parse("""
                <body><h1>Kế toán</h1><ul class="job-info-list"><li><span>Kinh nghiệm:</span><strong>2 năm</strong></li></ul>
                <div class="job-detail-card"><h2>Chi tiết</h2><p>Mô tả cũ</p></div></body>""", "https://jobs.example.com/a");
        Document newLayout = org.jsoup.Jsoup.parse("""
                <body><h1>Phục vụ</h1><dl class="gw-meta"><dt>Kinh nghiệm</dt><dd>Không yêu cầu</dd></dl>
                <div id="gw-detail-content"><h2>Chi tiết</h2><p>Mô tả mới</p></div></body>""", "https://jobs.example.com/b");
        Document oldLayout2 = org.jsoup.Jsoup.parse("""
                <body><h1>Tester</h1><ul class="job-info-list"><li><span>Kinh nghiệm:</span><strong>1 năm</strong></li></ul>
                <div class="job-detail-card"><h2>Chi tiết</h2><p>Mô tả 3</p></div></body>""", "https://jobs.example.com/c");
        StructureLlmClient llm = (page, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            pick(page, picks, JobField.EXPERIENCE_MONTHS, "strong, dd", "Kinh nghiệm");
            pick(page, picks, JobField.DESCRIPTION, "div.job-detail-card, #gw-detail-content", null);
            return new LlmSelection(true, picks, "fake", null, null);
        };
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover("jobs.example.com",
                List.of(new SamplePage("a", oldLayout), new SamplePage("b", newLayout), new SamplePage("c", oldLayout2)));

        String experience = result.toExtractorConfig().experienceMonths();
        assertTrue(experience.contains(" | "), experience);
        assertEquals(3, result.fields().get(JobField.EXPERIENCE_MONTHS).coverage(), result.report());
        assertEquals("Không yêu cầu", XPathEvaluator.evaluate(newLayout, experience, JobField.Kind.TEXT).value());
        assertEquals("1 năm", XPathEvaluator.evaluate(oldLayout2, experience, JobField.Kind.TEXT).value());
        assertEquals(3, result.fields().get(JobField.DESCRIPTION).coverage(), result.report());
    }

    @Test
    void unionKeepsTheAttributeStepOutside() {
        assertEquals("(//a/img | //div/img)/@src", HtmlStructureDiscoveryService.union("//a/img/@src", "//div/img/@src"));
        assertEquals("//h1 | //h2", HtmlStructureDiscoveryService.union("//h1", "//h2"));
        assertNull(HtmlStructureDiscoveryService.union("//img/@src", "//h1"));
    }

    @Test
    void onlyLlmSampleCountPagesAreSentToTheLlm() {
        AtomicInteger calls = new AtomicInteger();
        StructureLlmClient counting = (page, url) -> {
            calls.incrementAndGet();
            return FAKE_LLM.select(page, url);
        };
        new HtmlStructureDiscoveryService(counting, new HtmlSimplifier(), 2).discover("jobs.example.com", samples());
        assertEquals(2, calls.get());
    }

    @Test
    void ignoresUnknownNodeIdsAndNonJobPages() {
        StructureLlmClient bad = (page, url) -> url.endsWith("1.html")
                ? new LlmSelection(false, Map.of(), "fake", null, null)
                : new LlmSelection(true, Map.of(JobField.TITLE, new LlmSelection.FieldPick(999_999, null, null)),
                "fake", null, null);
        DiscoveryResult result = new HtmlStructureDiscoveryService(bad, new HtmlSimplifier(), 2)
                .discover("jobs.example.com", samples());
        assertTrue(result.fields().isEmpty());
        assertFalse(result.isComplete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not a job posting")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("unknown node")));
    }
}
