package org.example.jobpostingcrawler.fetcher;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.exception.FetchException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class Fetcher {

    @Value("${playwright.headless}")
    private boolean headless;

    private final ProxyProvider proxyProvider;

    private final ThreadLocal<PlaywrightResources> resources = new ThreadLocal<>();

    public Document getDocument(String url) {
        Browser browser = getResources().browser;
        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setLocale("vi-VN")
                        .setUserAgent(
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                        )
                        .setIgnoreHTTPSErrors(true)
        )) {
            Page page = context.newPage();
            Response response = page.navigate(
                    url,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(60_000)
            );
            if (response != null && response.status() >= 400) {
                throw new FetchException(url, response.status());
            }
            page.waitForTimeout(10_000);
            String html = page.content();
            return Jsoup.parse(html, url);
        }
    }

    private PlaywrightResources getResources() {
        PlaywrightResources current = resources.get();
        if (current == null) {
            current = new PlaywrightResources(headless, proxyProvider.getProxyForCurrentThread());
            resources.set(current);
        }
        return current;
    }

    public void closeForCurrentThread() {
        proxyProvider.clearForCurrentThread();
        PlaywrightResources current = resources.get();
        if (current == null) {
            return;
        }
        resources.remove();
        current.close();
    }

    private static final class PlaywrightResources {
        private final Playwright playwright;
        private final Browser browser;

        private PlaywrightResources(boolean headless, ProxyConfig proxyConfig) {
            playwright = Playwright.create();

            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(headless);
            if (proxyConfig != null) {
                Proxy proxy = new Proxy(proxyConfig.server());
                if (proxyConfig.hasCredentials()) {
                    proxy.setUsername(proxyConfig.username()).setPassword(proxyConfig.password());
                }
                options.setProxy(proxy);
                log.info("Crawl thread {} using proxy {}:{}", Thread.currentThread().getName(),
                        proxyConfig.host(), proxyConfig.port());
            }

            browser = playwright.chromium().launch(options);
        }

        private void close() {
            browser.close();
            playwright.close();
        }
    }
}
