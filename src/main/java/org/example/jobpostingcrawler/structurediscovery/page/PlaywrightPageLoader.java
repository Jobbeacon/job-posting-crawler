package org.example.jobpostingcrawler.structurediscovery.page;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.time.Duration;
import java.util.List;

/**
 * Own Playwright instance (independent from the crawler's Fetcher) with the same browser settings, so pages
 * rendered client-side (e.g. VietnamWorks) are seen exactly as the crawler will see them.
 * Not thread-safe: use one instance per thread and close it.
 */
@Slf4j
public class PlaywrightPageLoader implements PageLoader {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

    private final Playwright playwright;
    private final Browser browser;
    private final Duration settleTime;

    public PlaywrightPageLoader(boolean headless, Duration settleTime) {
        this.settleTime = settleTime;
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(List.of("--disable-blink-features=AutomationControlled")));
    }

    @Override
    public Document load(String url) {
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("vi-VN")
                .setUserAgent(USER_AGENT)
                .setIgnoreHTTPSErrors(true))) {
            Page page = context.newPage();
            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(60_000));
            if (response != null && response.status() >= 400) {
                throw new IllegalStateException("HTTP " + response.status() + " for " + url);
            }
            page.waitForTimeout(settleTime.toMillis());
            String html = page.content();
            log.debug("[StructureDiscovery] loaded {} ({} chars)", url, html.length());
            return Jsoup.parse(html, url);
        }
    }

    @Override
    public void close() {
        try {
            browser.close();
        } finally {
            playwright.close();
        }
    }
}
