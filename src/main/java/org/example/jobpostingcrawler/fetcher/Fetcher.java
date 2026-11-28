package org.example.jobpostingcrawler.fetcher;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.PlaywrightProperties;
import org.example.jobpostingcrawler.exception.FetchException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class Fetcher {

    // Decoding and painting these is most of the rendering work, and the crawler only reads the HTML.
    private static final Set<String> BLOCKED_RESOURCE_TYPES = Set.of("image", "media", "font");

    // Chromium net errors meaning the proxy itself is unreachable or refuses us, not the target site.
    private static final List<String> PROXY_ERRORS = List.of(
            "ERR_PROXY_", "ERR_TUNNEL_CONNECTION_FAILED", "ERR_NO_SUPPORTED_PROXIES",
            "ERR_MANDATORY_PROXY_CONFIGURATION_FAILED");

    private final PlaywrightProperties properties;
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
            if (properties.blockResources()) {
                context.route("**/*", route -> {
                    if (BLOCKED_RESOURCE_TYPES.contains(route.request().resourceType())) {
                        route.abort();
                    } else {
                        route.resume();
                    }
                });
            }
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
            page.waitForTimeout(properties.settleTime().toMillis());
            String html = page.content();
            return Jsoup.parse(html, url);
        }
    }

    /**
     * Called after a {@link PlaywrightException}: the browser of this thread may be unusable, so it is closed and
     * the next fetch launches a new one. When the proxy was the cause, the new browser gets another proxy.
     */
    public void recoverFromFailure(PlaywrightException e) {
        if (isProxyFailure(e)) {
            proxyProvider.markFailedForCurrentThread();
        }
        closeForCurrentThread();
    }

    public static boolean isProxyFailure(PlaywrightException e) {
        String message = e.getMessage();
        return message != null && PROXY_ERRORS.stream().anyMatch(message::contains);
    }

    private PlaywrightResources getResources() {
        PlaywrightResources current = resources.get();
        if (current == null) {
            current = new PlaywrightResources(properties.headless(), proxyProvider.getProxyForCurrentThread());
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
        try {
            current.close();
        } catch (PlaywrightException e) {
            log.warn("Failed to close the browser of thread {}: {}", Thread.currentThread().getName(), e.getMessage());
        }
    }

    private static final class PlaywrightResources {
        private final Playwright playwright;
        private final Browser browser;

        private PlaywrightResources(boolean headless, ProxyConfig proxyConfig) {
            playwright = Playwright.create();

            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setChannel("chromium")
                    .setArgs(List.of("--disable-blink-features=AutomationControlled"));
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
            try {
                browser.close();
            } finally {
                playwright.close();
            }
        }
    }
}
