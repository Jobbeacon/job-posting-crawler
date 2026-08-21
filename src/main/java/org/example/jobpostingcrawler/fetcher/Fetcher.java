package org.example.jobpostingcrawler.fetcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

@Slf4j
public class Fetcher {

    public static Document getDocument(String url) {

        Playwright playwright = Playwright.create();

        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
        );

        BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setLocale("vi-VN")
                        .setUserAgent(
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                        )
        );

        Page page = context.newPage();

        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000)
        );

        page.waitForTimeout(10_000);

        String html = page.content();

        browser.close();
        playwright.close();

        return Jsoup.parse(html, url);
    }

    public static JsonNode extractJosPosting(Document doc) {

//        Document doc = getDocument(
////                "https://www.topcv.vn/viec-lam/ke-toan-kho-uu-tien-nganh-san-xuat-luong-cung-8-11trieu-thang-di-lam-ngay-ha-noi/2211976.html?ta_source=BoxFeatureJob_LinkDetail"
////                "https://www.topcv.vn/viec-lam/mobile-developer-kotlin-thu-nhap-upto-20-trieu-lam-viec-tai-tu-liem-ha-noi-nghi-thu-7-chu-nhat/1179178.html?ta_source=JobSearchList_LinkDetail&u_sr_id=8oAId7TcCKjZ92agjKCvE3cJbyD0atqFuQpPFjoy_1783398296"
////                "https://itviec.com/it-jobs/devsecops-engineer-khoi-cong-nghe-thong-tin-mb-bank-2830?lab_feature=employer_job"
////                "https://www.vietnamworks.com/truong-phong-tao-nguon-va-thu-hut-tai-xe-bike-mass-recruitment-driver-acquisition-2085075-jv"
////                "https://glints.com/vn/opportunities/jobs/python-developer/9b3dfdf0-a6aa-46db-9f93-dcd8b6f37826"
//                "https://www.topcv.vn/viec-lam/junior-fullstack-developer-nestjs-reactjs/2246269.html"
//        );

        ObjectMapper mapper = new ObjectMapper();

        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = mapper.readTree(script.data());

                if ("JobPosting".equals(node.path("@type").asText())) {
                    return node;
                }
            } catch (JsonProcessingException e) {
                log.error("Invalid JSON, continue ...");
            }
        }
        return null;
    }
}