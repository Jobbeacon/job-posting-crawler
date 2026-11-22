package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.structurediscovery.simplify.HtmlSimplifier;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSimplifierTest {

    private final HtmlSimplifier simplifier = new HtmlSimplifier();

    private SimplifiedPage page() {
        return simplifier.simplify(StructureDiscoveryFixtures.jobPage("Java Developer", "Cty A", "2 năm",
                "30/12/2026", "15 - 20 triệu", "Hà Nội", true, "Java", "Spring"));
    }

    @Test
    void dropsScriptsStylesChromeAndModals() {
        String html = page().html();
        assertFalse(html.contains("xxxxx"), "script content must be removed");
        assertFalse(html.contains("color:red"));
        assertFalse(html.contains("Ngành 3"), "header navigation must be removed");
        assertFalse(html.contains("Đăng nhập để ứng tuyển"), "modals must be removed");
        assertFalse(html.contains("© Job board"), "footer must be removed");
    }

    @Test
    void keepsLabelValueGridAndFields() {
        String html = page().html();
        assertTrue(html.contains("Java Developer"));
        assertTrue(html.contains("Kinh nghiệm"));
        assertTrue(html.contains("2 năm"));
        assertTrue(html.contains("Hạn nộp hồ sơ"));
        assertTrue(html.contains("30/12/2026"));
        assertTrue(html.contains("Địa điểm"));
        assertTrue(html.contains("15 - 20 triệu"));
        assertTrue(html.contains("class=\"job-title\""));
    }

    @Test
    void collapsesRunsOfBigSimilarSiblings() {
        String html = page().html();
        assertTrue(html.contains("Việc liên quan 0"));
        assertFalse(html.contains("Việc liên quan 7"));
        assertTrue(html.contains("similar div.job-card"));
    }

    @Test
    void everyNodeIdMapsBackToTheOriginalDocument() {
        SimplifiedPage page = page();
        assertFalse(page.nodes().isEmpty());
        for (var entry : page.nodes().entrySet()) {
            assertTrue(page.html().contains("x=\"" + entry.getKey() + "\""));
            assertSame(page.document(), entry.getValue().ownerDocument());
        }
        assertNotNull(page.anchor());
        assertEquals("Java Developer", page.anchor().text());
    }

    @Test
    void keepsContentSectionsSharingUtilityClasses() {
        // Tailwind sites: every section has the same classes; they are content, not repeated cards
        StringBuilder sections = new StringBuilder();
        for (String title : List.of("Mô tả công việc", "Yêu cầu", "Quyền lợi", "Địa điểm làm việc", "Thời gian")) {
            sections.append("<div class=\"flex flex-col gap-3 w-full\"><h2 class=\"text-20\">").append(title)
                    .append("</h2><ul><li>Một</li><li>Hai</li><li>Ba</li><li>Bốn</li><li>Năm</li><li>Sáu</li></ul></div>");
        }
        SimplifiedPage page = simplifier.simplify(Jsoup.parse("<body><h1>Sales</h1>" + sections + "</body>"));
        assertTrue(page.html().contains("Địa điểm làm việc"), page.html());
        assertTrue(page.html().contains("Thời gian"));
    }

    @Test
    void dropsBootstrapAndTailwindHiddenElementsUnlessShownResponsively() {
        Document doc = Jsoup.parse("""
                <body><h1>Tester</h1>
                <div class="location-extra d-none">Địa chỉ trùng lặp</div>
                <span class="label d-none d-sm-inline-block">Địa điểm:</span>
                <div class="hidden">Ẩn</div><div class="hidden md:flex">Desktop menu text</div>
                </body>""");
        String html = simplifier.simplify(doc).html();
        assertFalse(html.contains("Địa chỉ trùng lặp"));
        assertFalse(html.contains("Ẩn<"));
        assertTrue(html.contains("Địa điểm:"));
        assertTrue(html.contains("Desktop menu text"));
    }

    @Test
    void findsTitleThatIsNotAHeading() {
        Document doc = Jsoup.parse("""
                <html><head><meta property="og:title" content="Recruiting Kế toán trưởng at Lotte Engineering & Construction Co.,Ltd. | Saramin"></head>
                <body><div><a class="line-clamp-2 font-semibold">Kế toán trưởng</a>
                <a class="company">Lotte Engineering & Construction Co.,Ltd.</a></div><p>Mô tả công việc dài ...</p></body></html>""");
        SimplifiedPage page = simplifier.simplify(doc);
        assertNotNull(page.anchor());
        assertEquals("Kế toán trưởng", page.anchor().text());
    }

    @Test
    void skipsPassThroughWrappers() {
        Document doc = Jsoup.parse("<body><h1>T</h1><div class=\"a\"><div class=\"b\"><p>Hello world</p></div></div></body>");
        SimplifiedPage page = simplifier.simplify(doc);
        assertFalse(page.html().contains("class=\"a\""));
        assertTrue(page.html().contains("<p x="));
    }

    @Test
    void focusesOnTheJobRegionWhenThePageIsTooBig() {
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            noise.append("<div class=\"n").append(i % 7).append("\"><p>Noise paragraph number ").append(i)
                    .append(" with some text</p><span>").append(i).append("</span></div>");
        }
        Document doc = Jsoup.parse("<body><div class=\"noise\">" + noise + "</div><div class=\"job\"><h1>Tester</h1>"
                + "<div class=\"desc\">" + "Mô tả công việc chi tiết. ".repeat(40) + "</div></div></body>");
        SimplifiedPage page = new HtmlSimplifier(new HtmlSimplifier.Options(3_000, 120, 3)).simplify(doc);
        assertTrue(page.html().length() <= 3_100, page.stats());
        assertTrue(page.html().contains("Tester"));
        Element focus = page.focus();
        assertTrue(focus.hasClass("job"), "focus should be the job container but was " + focus.cssSelector());
    }
}
