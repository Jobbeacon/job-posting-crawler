package org.example.jobpostingcrawler.structurediscovery.page;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.fetcher.ProxyConfig;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.time.Duration;
import java.util.List;

/**
 * Own Playwright instance (independent from the crawler's Fetcher) with the same browser settings and proxy, so
 * pages rendered client-side (e.g. VietnamWorks) are seen exactly as the crawler will see them, and sites that block
 * the server's own IP (HTTP 403) serve the discovery like they serve the crawler.
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
        this(headless, settleTime, null);
    }

    /** @param proxy proxy of the browser, null to connect directly */
    public PlaywrightPageLoader(boolean headless, Duration settleTime, ProxyConfig proxy) {
        this.settleTime = settleTime;
        this.playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setChannel("chromium")
                .setArgs(List.of("--disable-blink-features=AutomationControlled"));
        if (proxy != null) {
            Proxy playwrightProxy = new Proxy(proxy.server());
            if (proxy.hasCredentials()) {
                playwrightProxy.setUsername(proxy.username()).setPassword(proxy.password());
            }
            options.setProxy(playwrightProxy);
            log.info("[StructureDiscovery] browser using proxy {}:{}", proxy.host(), proxy.port());
        }
        try {
            this.browser = playwright.chromium().launch(options);
        } catch (RuntimeException e) {
            playwright.close();
            throw e;
        }
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
