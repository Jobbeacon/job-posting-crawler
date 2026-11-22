package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.structurediscovery.HtmlStructureDiscoveryService.SamplePage;
import org.example.jobpostingcrawler.structurediscovery.llm.LlmSelection;
import org.example.jobpostingcrawler.structurediscovery.llm.StructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.page.PageLoader;
import org.example.jobpostingcrawler.structurediscovery.simplify.HtmlSimplifier;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathCandidateGenerator;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathEvaluator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issues found on live sites (VietnamWorks, ITviec, TopCV). */
class StructureDiscoveryFixesTest {

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

    // ---------------------------------------------------------------- styled-components classes (VietnamWorks)

    @Test
    void cssInJsClassesAreGenerated() {
        Set<String> styled = Set.of("sc-d1ecfd52-0", "bSkXqn");
        assertTrue(GeneratedClasses.isGenerated("sc-d1ecfd52-0", styled));
        assertTrue(GeneratedClasses.isGenerated("bSkXqn", styled));
        assertTrue(GeneratedClasses.isGenerated("jsx-f6f85871d7bde676", Set.of("jsx-f6f85871d7bde676")));
        // a camelCase class is only a hash next to a CSS-in-JS class
        assertFalse(GeneratedClasses.isGenerated("jobTitle", Set.of("jobTitle", "header")));
        assertFalse(GeneratedClasses.isGenerated("footer__link-head", styled));
    }

    @Test
    void generatedClassesAreNotUsedInXpaths() {
        Element e = Jsoup.parse("<a class=\"sc-c24201be-2 kzLqgg company-name\">Cty A</a>").selectFirst("a");
        assertEquals(List.of("company-name"), XPathCandidateGenerator.stableClasses(e));
    }

    @Test
    void generatedClassesAreHiddenFromTheLlm() {
        Document doc = Jsoup.parse("<body><h1 class=\"sc-aa608f0d-0 hmvhgA title\">Dev</h1></body>");
        String html = new HtmlSimplifier().simplify(doc).html();
        assertTrue(html.contains("class=\"title\""), html);
        assertFalse(html.contains("hmvhgA"), html);
        assertFalse(html.contains("sc-aa608f0d"), html);
    }

    // ---------------------------------------------------------------- list picked on the caption row (ITviec)

    private static Document itviecPage(String title, String... domains) {
        StringBuilder tags = new StringBuilder();
        for (String d : domains) {
            tags.append("<div class=\"itag\">").append(d).append("</div>");
        }
        return Jsoup.parse("<body><h1>" + title + "</h1><section class=\"job-content\"><p>Mô tả " + title
                + "</p></section><div class=\"d-flex flex-column\"><div class=\"w-fixed\">Job Domain:</div>"
                + "<div class=\"d-flex flex-wrap\">" + tags + "</div></div></body>", "https://itviec.com/it-jobs/x");
    }

    @Test
    void listPickedOnTheCaptionRowUsesTheValuesContainer() {
        StructureLlmClient llm = (page, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            pick(page, picks, JobField.INDUSTRY, "div.flex-column", "Job Domain:"); // the row, caption included
            return new LlmSelection(true, picks, "fake", null, null);
        };
        Document a = itviecPage("Java Dev", "Banking", "Fintech");
        Document b = itviecPage("Tester", "Logistics");
        Document c = itviecPage("DBA", "Retail", "E-commerce");
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover("itviec.com",
                List.of(new SamplePage("a", a), new SamplePage("b", b), new SamplePage("c", c)));

        String industry = result.toExtractorConfig().industry();
        assertEquals("Banking, Fintech", XPathEvaluator.evaluate(a, industry, JobField.Kind.LIST).value(), industry);
        assertEquals("Retail, E-commerce", XPathEvaluator.evaluate(c, industry, JobField.Kind.LIST).value());
    }

    @Test
    void valueContainerKeepsRealContainers() {
        Element tags = Jsoup.parse("<div><a>Java</a><a>SQL</a></div>").selectFirst("div");
        assertEquals(tags, HtmlStructureDiscoveryService.valueContainer(JobField.SKILLS, tags, "Skills:"));
        Element row = Jsoup.parse("<div><span>Kỹ năng</span><div><a>Java</a></div></div>").selectFirst("div");
        assertEquals("Java", HtmlStructureDiscoveryService.valueContainer(JobField.SKILLS, row, "Kỹ năng").text());
        // text fields are never changed
        assertEquals(row, HtmlStructureDiscoveryService.valueContainer(JobField.LOCATION, row, "Kỹ năng"));
    }

    // ---------------------------------------------------------------- location class shared with other fields

    @Test
    void locationDoesNotJoinOtherFieldsSharingItsClass() {
        StructureLlmClient llm = (page, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            pick(page, picks, JobField.LOCATION, "div.address span.normal-text", null);
            return new LlmSelection(true, picks, "fake", null, null);
        };
        java.util.function.Function<String, Document> page = address -> Jsoup.parse("<body><h1>Dev</h1>"
                + "<div class=\"address\"><span class=\"normal-text\">" + address + "</span></div>"
                + "<div class=\"mode\"><span class=\"normal-text\">Hybrid</span></div>"
                + "<div class=\"posted\"><span class=\"normal-text\">Posted 5 days ago</span></div></body>");
        Document a = page.apply("An Phu Plaza, Ho Chi Minh");
        Document b = page.apply("Keangnam, Ha Noi");
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover("itviec.com",
                List.of(new SamplePage("a", a), new SamplePage("b", b)));

        String location = result.toExtractorConfig().location();
        assertEquals("An Phu Plaza, Ho Chi Minh", XPathEvaluator.evaluate(a, location, JobField.Kind.TEXT).value(),
                result.report());
    }

    @Test
    void siblingLocationsAreStillJoined() {
        StructureLlmClient llm = (page, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            pick(page, picks, JobField.LOCATION, "ul.locations li", null);
            return new LlmSelection(true, picks, "fake", null, null);
        };
        Document a = Jsoup.parse("<body><h1>Dev</h1><ul class=\"locations\"><li class=\"loc\">Hà Nội</li>"
                + "<li class=\"loc\">Hồ Chí Minh</li></ul></body>");
        Document b = Jsoup.parse("<body><h1>QA</h1><ul class=\"locations\"><li class=\"loc\">Đà Nẵng</li></ul></body>");
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover("x.vn",
                List.of(new SamplePage("a", a), new SamplePage("b", b)));

        String location = result.toExtractorConfig().location();
        assertEquals("Hà Nội|Hồ Chí Minh", XPathEvaluator.evaluate(a, location, JobField.Kind.TEXT).value(),
                result.report());
    }

    // ---------------------------------------------------------------- requirement tags are not skills (TopCV)

    @Test
    void requirementTagsAreNotSkills() {
        assertTrue(HtmlStructureDiscoveryService.looksLikeRequirements(List.of(
                "1 năm kinh nghiệm chuyên môn, Đại Học trở lên",
                "Không yêu cầu kinh nghiệm chuyên môn, Tuổi 19 - 30, Trung học phổ thông (Cấp 3) trở lên",
                "<empty>")));
        assertFalse(HtmlStructureDiscoveryService.looksLikeRequirements(List.of(
                "Java, Spring Boot, SQL", "Excel, Giao tiếp, Tiếng Anh")));
        assertFalse(HtmlStructureDiscoveryService.looksLikeRequirements(List.of("<empty>")));
    }

    // ---------------------------------------------------------------- LLM XPath filtering on the value itself (ITviec)

    @Test
    void xpathFilteringOnItsOwnValueDependsOnTheValue() {
        // gemini-3.1-flash-lite suggested this for ITviec location: works only for jobs in Ho Chi Minh
        assertTrue(HtmlStructureDiscoveryService.dependsOnTheValue(
                "//span[contains(@class, 'normal-text') and contains(text(), 'Ho Chi Minh')]",
                List.of("4th Floor An Phu Plaza, Xuan Hoa Ward, Ho Chi Minh")));
        // caption of another element, class names: fine
        assertFalse(HtmlStructureDiscoveryService.dependsOnTheValue(
                "//div[normalize-space(.)='Kinh nghiệm']/following-sibling::div[1]", List.of("2 năm")));
        assertFalse(HtmlStructureDiscoveryService.dependsOnTheValue(
                "//span[contains(@class,'normal-text')]", List.of("normal-text address")));
    }

    @Test
    void llmXpathFilteringOnTheValueIsNotChosen() {
        StructureLlmClient llm = (page, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            Element address = page.document().selectFirst("span.addr");
            Map<Element, Integer> ids = new IdentityHashMap<>();
            page.nodes().forEach((id, el) -> ids.put(el, id));
            picks.put(JobField.LOCATION, new LlmSelection.FieldPick(ids.get(address), null,
                    "//span[contains(text(), 'Ho Chi Minh')]"));
            return new LlmSelection(true, picks, "fake", null, null);
        };
        java.util.function.Function<String, Document> page = address -> Jsoup.parse("<body><h1>Dev</h1>"
                + "<div class=\"meta\"><span class=\"addr\">" + address + "</span><span class=\"mode\">Hybrid</span>"
                + "</div></body>");
        Document a = page.apply("An Phu Plaza, Ho Chi Minh");
        Document b = page.apply("Long Tower, Ho Chi Minh");
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover("itviec.com",
                List.of(new SamplePage("a", a), new SamplePage("b", b)));

        String location = result.toExtractorConfig().location();
        assertFalse(location.contains("Ho Chi Minh"), location);
        assertEquals("Keangnam, Ha Noi",
                XPathEvaluator.evaluate(page.apply("Keangnam, Ha Noi"), location, JobField.Kind.TEXT).value());
    }

    // ---------------------------------------------------------------- job level is not the employment type (VietnamWorks)

    @Test
    void jobLevelIsNotTheEmploymentType() {
        // gemini-2.5-flash mapped VietnamWorks "CẤP BẬC: Nhân viên" to employment type
        assertTrue(HtmlStructureDiscoveryService.looksLikeJobLevel(
                "//label[normalize-space(.)='CẤP BẬC']/following-sibling::p[1]", List.of("Nhân viên")));
        assertTrue(HtmlStructureDiscoveryService.looksLikeJobLevel(
                "//span[contains(@class,'level')]", List.of("Nhân viên", "Trưởng phòng", "Senior Manager")));
        assertFalse(HtmlStructureDiscoveryService.looksLikeJobLevel(
                "//h3[normalize-space(.)='Loại hình làm việc']/following-sibling::div[1]",
                List.of("Toàn thời gian", "Bán thời gian")));
        // "Nhân viên chính thức - Toàn thời gian" is still a working form
        assertFalse(HtmlStructureDiscoveryService.looksLikeJobLevel(
                "//div[@class='type']", List.of("Nhân viên chính thức - Toàn thời gian", "Part-time")));
    }

    @Test
    void employmentTypeReadingTheLevelIsDropped() {
        Document page = Jsoup.parse("""
                <body><h1>Dev</h1><div><label>CẤP BẬC</label><p>Nhân viên</p></div>
                <div><label>NGÀY ĐĂNG</label><p>12/11/2026</p></div></body>""");
        StructureLlmClient llm = (p, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(p, picks, JobField.TITLE, "h1", null);
            pick(p, picks, JobField.EMPLOYMENT_TYPE, "div:nth-of-type(1) p", "CẤP BẬC");
            return new LlmSelection(true, picks, "fake", null, null);
        };
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 1)
                .discover("vietnamworks.com", List.of(new SamplePage("a", page)));
        assertNull(result.toExtractorConfig().employmentType(), result.report());
    }

    @Test
    void infoBoxRowsAreNotAList() {
        // gemini-3.1-flash-lite picked TopCV's whole "Tổng quan" box as skills
        assertTrue(HtmlStructureDiscoveryService.looksLikeLabelledRows(List.of(
                "Yêu cầu: 1 năm kinh nghiệm chuyên môn Cao đẳng trở lên, Quyền lợi: Bảo hiểm xã hội, "
                        + "Chuyên môn: Kế toán tổng hợp")));
        assertFalse(HtmlStructureDiscoveryService.looksLikeLabelledRows(List.of(
                "Java, Spring Boot, SQL", "C#: .NET, Excel")));
        assertFalse(HtmlStructureDiscoveryService.looksLikeLabelledRows(List.of("Banking, Software Products")));
    }

    @Test
    void skillsCandidatesHoldingRequirementsAreDropped() {
        Document page = Jsoup.parse("""
                <body><h1>Kế toán</h1><h3>Yêu cầu:</h3><div class="tags"><span>2 năm kinh nghiệm</span>
                <span>Đại học trở lên</span></div></body>""", "https://www.topcv.vn/viec-lam/a/1.html");
        StructureLlmClient llm = (p, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(p, picks, JobField.TITLE, "h1", null);
            pick(p, picks, JobField.SKILLS, "div.tags", "Yêu cầu:");
            return new LlmSelection(true, picks, "fake", null, null);
        };
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 1)
                .discover("topcv.vn", List.of(new SamplePage("a", page)));

        assertNull(result.toExtractorConfig().skills(), result.report());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("requirement tags")), result.report());
    }

    @Test
    void skillsXpathSelectingTheIndustriesIsDropped() {
        // TopCV: several "required-tag" blocks; the first one is the industries unless the job lists skills first
        java.util.function.BiFunction<String, String, Document> page = (industries, skills) -> Jsoup.parse(
                "<body><h1>Job</h1>"
                        + (skills == null ? "" : "<div class=\"required-tag\"><h3>Kỹ năng cần có</h3>"
                        + "<div class=\"required-tag__content--desc\">" + skills + "</div></div>")
                        + "<div class=\"required-tag\"><h3>Chuyên môn</h3>"
                        + "<div class=\"required-tag__content--desc\">" + industries + "</div></div></body>");
        Document a = page.apply("<span>Xây dựng</span>", null);
        Document b = page.apply("<span>Bán lẻ</span><span>Thời trang</span>",
                "<span>Giao tiếp</span><span>Bán hàng</span>");
        Document c = page.apply("<span>Tài chính</span><span>Kế toán</span>", null);
        StructureLlmClient llm = (p, url) -> {
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(p, picks, JobField.TITLE, "h1", null);
            pick(p, picks, JobField.INDUSTRY, "div.required-tag:last-child .required-tag__content--desc", "Chuyên môn");
            // the LLM only analysed the page that has skills
            if (p.document().text().contains("Kỹ năng")) {
                pick(p, picks, JobField.SKILLS, "div.required-tag__content--desc", "Kỹ năng cần có");
            }
            return new LlmSelection(true, picks, "fake", null, null);
        };
        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 1).discover("topcv.vn",
                List.of(new SamplePage("b", b), new SamplePage("a", a), new SamplePage("c", c)));

        String skills = result.toExtractorConfig().skills();
        if (skills != null) {
            assertEquals("", XPathEvaluator.evaluate(a, skills, JobField.Kind.LIST).isEmpty() ? ""
                    : XPathEvaluator.evaluate(a, skills, JobField.Kind.LIST).value(), result.report());
            assertEquals("Giao tiếp, Bán hàng", XPathEvaluator.evaluate(b, skills, JobField.Kind.LIST).value());
        }
    }

    // ---------------------------------------------------------------- homepage seed links more companies than jobs

    @Test
    void nextUrlGroupIsTriedWhenTheLlmRejectsTheFirstOne() {
        String seedHtml = """
                <a href="/companies/cong-ty-a-c422025">a</a><a href="/companies/cong-ty-b-c336684">b</a>
                <a href="/companies/cong-ty-c-c111222">c</a><a href="/companies/cong-ty-d-c333444">d</a>
                <a href="/java-dev-1790924584127918890-2115086-jv">j1</a>
                <a href="/tester-1790924661878896770-2115087-jv">j2</a>
                """;
        PageLoader loader = url -> url.endsWith("vietnamworks.com")
                ? Jsoup.parse(seedHtml, url)
                : Jsoup.parse("<body><h1>" + (url.contains("/companies/") ? "Company" : "Job " + url.length())
                + "</h1></body>", url);
        AtomicInteger companyCalls = new AtomicInteger();
        StructureLlmClient llm = (page, url) -> {
            if (url.contains("/companies/")) {
                companyCalls.incrementAndGet();
                return new LlmSelection(false, Map.of(), "fake", null, null);
            }
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            return new LlmSelection(true, picks, "fake", null, null);
        };

        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover(loader,
                List.of("https://www.vietnamworks.com"), "https://www.vietnamworks.com/", null, 4);

        assertEquals("//h1", result.toExtractorConfig().title(), result.report());
        assertTrue(result.sampleUrls().stream().allMatch(u -> u.endsWith("-jv")), result.sampleUrls().toString());
        assertEquals(2, companyCalls.get(), "stops after llm-sample-count rejected pages");
    }

    @Test
    void listingPagesAreUsedWhenTheSeedLinksNoJobPage() {
        // VietnamWorks homepage once rendered: company links and menu links only, the job list comes later
        String seedHtml = """
                <a href="/companies/cong-ty-a-c422025">a</a><a href="/companies/cong-ty-b-c336684">b</a>
                <a href="/gioi-thieu">about</a><a href="/tim-viec-lam">jobs</a>
                <a href="https://other.com/tim-viec-lam">other site</a>
                """;
        String listingHtml = """
                <a href="/java-dev-1790924584127918890-2115086-jv">j1</a>
                <a href="/tester-1790924661878896770-2115087-jv">j2</a>
                <a href="/ke-toan-1793867821381939256-2128166-jv">j3</a>
                """;
        List<String> loaded = new java.util.ArrayList<>();
        PageLoader loader = url -> {
            loaded.add(url);
            if (url.equals("https://www.vietnamworks.com")) {
                return Jsoup.parse(seedHtml, url);
            }
            if (url.endsWith("/tim-viec-lam")) {
                return Jsoup.parse(listingHtml, url);
            }
            return Jsoup.parse("<body><h1>" + (url.contains("/companies/") ? "Company" : "Job") + "</h1></body>", url);
        };
        StructureLlmClient llm = (page, url) -> {
            if (url.contains("/companies/")) {
                return new LlmSelection(false, Map.of(), "fake", null, null);
            }
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            pick(page, picks, JobField.TITLE, "h1", null);
            return new LlmSelection(true, picks, "fake", null, null);
        };

        DiscoveryResult result = new HtmlStructureDiscoveryService(llm, new HtmlSimplifier(), 2).discover(loader,
                List.of("https://www.vietnamworks.com"), "https://www.vietnamworks.com/", null, 4);

        assertEquals("//h1", result.toExtractorConfig().title(), result.report());
        assertEquals(3, result.sampleUrls().size(), result.sampleUrls().toString());
        assertTrue(result.sampleUrls().stream().allMatch(u -> u.endsWith("-jv")));
        // job words first (/tim-viec-lam before /gioi-thieu); other hosts are ignored
        assertEquals(List.of("https://www.vietnamworks.com/tim-viec-lam", "https://www.vietnamworks.com/gioi-thieu"),
                org.example.jobpostingcrawler.structurediscovery.page.SampleUrlCollector.listingPages(
                        Jsoup.parse(seedHtml, "https://www.vietnamworks.com"), 3));
        assertFalse(loaded.contains("https://other.com/tim-viec-lam"));
    }
}
