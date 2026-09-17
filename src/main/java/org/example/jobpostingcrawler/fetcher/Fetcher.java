package org.example.jobpostingcrawler.fetcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class Fetcher {

    @Value("${playwright.headless}")
    private boolean headless;

    private ObjectMapper objectMapper;

    public Document getDocument(String url) {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(headless)
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
}