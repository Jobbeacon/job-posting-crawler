package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.structurediscovery.xpath.XPathCandidate;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathCandidateGenerator;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathEvaluator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XPathCandidateGeneratorTest {

    private final XPathCandidateGenerator generator = new XPathCandidateGenerator();

    private Document doc() {
        return StructureDiscoveryFixtures.jobPage("Java Developer", "Cty A", "2 năm", "30/12/2026",
                "15 - 20 triệu", "Hà Nội", true, "Java", "Spring");
    }

    @Test
    void everyCandidateSelectsTheTarget() {
        Document doc = doc();
        Element target = doc.selectFirst("h1.job-title");
        List<XPathCandidate> candidates = generator.generate(doc, target, JobField.TITLE, null, null);
        assertFalse(candidates.isEmpty());
        for (XPathCandidate c : candidates) {
            assertEquals("Java Developer",
                    XPathEvaluator.evaluate(doc, c.xpath(), JobField.Kind.TEXT).value(), c.toString());
        }
        assertTrue(candidates.stream().anyMatch(c -> c.xpath().equals("//h1[contains(@class,'job-title')]")));
    }

    @Test
    void buildsLabelAnchoredXpathForLabelValueGrid() {
        Document doc = doc();
        Element target = doc.select("div.info-value").get(0); // "2 năm" under "Kinh nghiệm"
        List<XPathCandidate> candidates = generator.generate(doc, target, JobField.EXPERIENCE_MONTHS, "Kinh nghiệm", null);
        XPathCandidate label = candidates.stream().filter(c -> c.strategy().equals("label")).findFirst().orElseThrow();
        assertTrue(label.xpath().contains("'Kinh nghiệm'"), label.xpath());
        assertTrue(label.xpath().contains("following-sibling::div[1]"), label.xpath());
        assertEquals("2 năm", XPathEvaluator.evaluate(doc, label.xpath(), JobField.Kind.TEXT).value());
    }

    @Test
    void classOnlyXpathIsRejectedWhenTargetIsNotFirstMatch() {
        Document doc = doc();
        Element target = doc.select("div.info-value").get(1); // deadline
        List<XPathCandidate> candidates = generator.generate(doc, target, JobField.EXPIRED_DATE, "Hạn nộp hồ sơ", null);
        assertTrue(candidates.stream().noneMatch(c -> c.xpath().equals("//div[contains(@class,'info-value')]")));
        assertTrue(candidates.stream().allMatch(c ->
                XPathEvaluator.evaluate(doc, c.xpath(), JobField.Kind.TEXT).first() == target));
    }

    @Test
    void attributeFieldEndsWithAttributeStep() {
        Document doc = doc();
        Element container = doc.selectFirst("div.company");
        List<XPathCandidate> candidates = generator.generate(doc, container, JobField.COMPANY_LOGO_URL, null, null);
        assertFalse(candidates.isEmpty());
        for (XPathCandidate c : candidates) {
            assertTrue(c.xpath().endsWith("/@src"), c.xpath());
        }
        assertTrue(XPathEvaluator.evaluate(doc, candidates.get(0).xpath(), JobField.Kind.ATTRIBUTE).value()
                .startsWith("https://cdn.example.com/"));
    }

    @Test
    void lazyImagesUseDataSrc() {
        Document doc = Jsoup.parse("<body><img class=\"logo\" src=\"data:image/gif;base64,R0\" data-src=\"https://x/l.png\"></body>");
        List<XPathCandidate> candidates = generator.generate(doc, doc.selectFirst("img"), JobField.COMPANY_LOGO_URL, null, null);
        assertTrue(candidates.get(0).xpath().endsWith("/@data-src"), candidates.get(0).xpath());
    }

    @Test
    void utilityClassesRankBelowStructuralCandidates() {
        Document doc = Jsoup.parse("""
                <body><nav><span class="text-20">Menu</span></nav>
                <h1 class="text-20 font-semibold leading-8">Sales B2B</h1></body>""");
        List<XPathCandidate> candidates = generator.generate(doc, doc.selectFirst("h1"), JobField.TITLE, null, null);
        XPathCandidate best = candidates.stream().min(java.util.Comparator.comparingInt(XPathCandidate::priority)).orElseThrow();
        assertEquals("//h1", best.xpath());
        assertTrue(candidates.stream().noneMatch(c -> c.xpath().contains("contains(@class,'text-20')")),
                "utility classes must use exact-token matching: " + candidates);
    }

    @Test
    void containerIsAnchoredOnItsHeading() {
        Document doc = Jsoup.parse("""
                <body><h1>Sales</h1>
                <div class="flex flex-col"><div class="flex"><h2 class="text-20">Mô tả công việc</h2></div>
                <ul><li>Tìm khách hàng</li><li>Chăm sóc khách hàng</li></ul></div></body>""");
        Element container = doc.select("div.flex.flex-col").first();
        List<XPathCandidate> candidates = generator.generate(doc, container, JobField.DESCRIPTION, null, null);
        assertTrue(candidates.stream().anyMatch(c -> c.xpath().equals("//h2[normalize-space(.)='Mô tả công việc']/../..")),
                candidates.toString());
    }

    @Test
    void labelWithIconLigatureUsesItsOwnTextNode() {
        Document doc = Jsoup.parse("""
                <body><h1>Kỹ sư</h1><div class="item"><span class="label"><i class="material-icons">event</i>Hết hạn nộp</span>
                <strong class="value">13/12/2026</strong></div></body>""");
        List<XPathCandidate> candidates = generator.generate(doc, doc.selectFirst("strong"), JobField.EXPIRED_DATE,
                "Hết hạn nộp", null);
        XPathCandidate label = candidates.stream().filter(c -> c.strategy().equals("label")).findFirst().orElseThrow();
        assertEquals("//span[text()[normalize-space(.)='Hết hạn nộp']]/following-sibling::strong[1]", label.xpath());
    }

    @Test
    void captionAndValueInOneElementUseTheCaptionPrefix() {
        // 123job: caption and value share the element; related-job cards below repeat the same caption
        Document doc = Jsoup.parse("""
                <body><h1>Nhân viên bếp</h1>
                <div><span class="inline-flex text-gray-600">Đăng: 8 giờ trước</span></div>
                <div class="related"><a><span class="inline-flex text-gray-600">Đăng: 1 giờ trước</span></a>
                <a><span class="inline-flex text-gray-600">Đăng: 6 giờ trước</span></a></div></body>""");
        Element target = doc.select("span").first();
        List<XPathCandidate> candidates = generator.generate(doc, target, JobField.POSTED_DATE, null, null);
        assertTrue(candidates.stream().anyMatch(c ->
                c.xpath().equals("(//span[starts-with(normalize-space(.),'Đăng:')])[1]")), candidates.toString());
        assertEquals("Đăng: 8 giờ trước", XPathEvaluator.evaluate(doc,
                "(//span[starts-with(normalize-space(.),'Đăng:')])[1]", JobField.Kind.TEXT).value());
    }

    @Test
    void literalHandlesQuotes() {
        assertEquals("'abc'", XPathCandidateGenerator.literal("abc"));
        assertEquals("\"it's\"", XPathCandidateGenerator.literal("it's"));
        assertEquals("concat('a',\"'\",'b\"c')", XPathCandidateGenerator.literal("a'b\"c"));
    }
}
