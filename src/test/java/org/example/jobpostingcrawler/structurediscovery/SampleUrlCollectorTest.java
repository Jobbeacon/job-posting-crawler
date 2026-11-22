package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.structurediscovery.page.SampleUrlCollector;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleUrlCollectorTest {

    @Test
    void picksJobDetailLinksEvenWhenPrefixIsTheSiteRoot() {
        Document seed = Jsoup.parse("""
                <a href="/tim-viec-lam">search</a>
                <a href="/nha-tuyen-dung/cong-ty-a">company</a>
                <a href="/java-developer-1790924584127918890-2115086-jv?source=home">job 1</a>
                <a href="/tester-1790924661878896770-2115087-jv">job 2</a>
                <a href="/ke-toan-1793867821381939256-2128166-jv">job 3</a>
                <a href="/blog/2024/01/bai-viet-1234">blog</a>
                <a href="https://other.com/job-123456">other site</a>
                """, "https://www.vietnamworks.com/tim-viec-lam");
        List<String> urls = SampleUrlCollector.collect(seed, "https://www.vietnamworks.com/", 2);
        assertEquals(2, urls.size());
        assertTrue(urls.stream().allMatch(u -> u.endsWith("-jv")), urls.toString());
    }

    @Test
    void respectsPrefix() {
        Document seed = Jsoup.parse("""
                <a href="https://www.topcv.vn/viec-lam/java/2344918.html">job</a>
                <a href="https://www.topcv.vn/cong-ty/abc/105.html">company</a>
                <a href="https://www.topcv.vn/viec-lam/tester/2344854.html?ta_source=x">job</a>
                """, "https://www.topcv.vn/viec-lam");
        assertEquals(List.of("https://www.topcv.vn/viec-lam/java/2344918.html",
                        "https://www.topcv.vn/viec-lam/tester/2344854.html"),
                SampleUrlCollector.collect(seed, "https://www.topcv.vn/viec-lam/", 5));
    }

    @Test
    void groupsUrlsWithHashOrMixedCaseIds() {
        Document careerViet = Jsoup.parse("""
                <a href="/vi/tim-viec-lam/nhan-vien-hse-long-an.35C8DD20.html">1</a>
                <a href="/vi/tim-viec-lam/giam-sat-hien-truong-xay-dung.35C8886D.html">2</a>
                <a href="/vi/tim-viec-lam/pho-giam-doc-2026td457951.35C8DCE6.html">3</a>
                <a href="/vi/tim-viec-lam/ke-toan">category</a>
                """, "https://careerviet.vn/viec-lam/tat-ca-viec-lam-vi.html");
        assertEquals(3, SampleUrlCollector.collect(careerViet, "https://careerviet.vn/vi/tim-viec-lam/", 5).size());

        Document job123 = Jsoup.parse("""
                <a href="/viec-lam/nhan-vien-bep-khach-san-5-sao-tai-da-lat-vrzBGQ27r0">1</a>
                <a href="/viec-lam/video-editor-full-time-2026-BDnBzQJvDR">2</a>
                <a href="/viec-lam/nhan-vien-dao-tao-n9RwoJkmrb">3</a>
                <a href="/viec-lam/ha-noi">location page</a>
                """, "https://123job.vn/tuyen-dung");
        assertEquals(3, SampleUrlCollector.collect(job123, "https://123job.vn/viec-lam/", 5).size());
    }

    @Test
    void slugsWithDigitsStayInTheSameGroup() {
        Document seed = Jsoup.parse("""
                <a href="https://www.topcv.vn/viec-lam/nhan-vien-cham-soc-khach-hang-stk-tech/2357092.html">1</a>
                <a href="https://www.topcv.vn/viec-lam/quan-ly-sieu-thi-thu-nhap-14-28-trieu/2345455.html">2</a>
                <a href="https://www.topcv.vn/viec-lam/ke-toan-2026/2351219.html">3</a>
                """, "https://www.topcv.vn/tim-viec-lam-moi-nhat");
        assertEquals(3, SampleUrlCollector.collect(seed, "https://www.topcv.vn/viec-lam/", 5).size());
    }
}
