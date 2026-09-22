package org.example.jobpostingcrawler.fetcher;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class Fetcher {

    @Value("${playwright.headless}")
    private boolean headless;

    private Playwright playwright;
    private Browser browser;

    @PostConstruct
    public void start() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(headless)
        );
    }

    @PreDestroy
    public void stop() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    public Document getDocument(String url) {
        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setLocale("vi-VN")
                        .setUserAgent(
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                        )
        )) {
            Page page = context.newPage();
            page.navigate(
                    url,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60_000)
            );
            page.waitForTimeout(10_000);
            String html = page.content();
            return Jsoup.parse(html, url);
        }
    }
}