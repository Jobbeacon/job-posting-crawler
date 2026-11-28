package org.example.jobpostingcrawler.fetcher;

import com.microsoft.playwright.PlaywrightException;
import org.example.jobpostingcrawler.config.PlaywrightProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyProviderTest {

    private ProxyProvider provider(String... proxies) {
        return new ProxyProvider(new PlaywrightProperties(true, List.of(proxies), null, null, Duration.ofMinutes(5)));
    }

    @Test
    void failedProxyIsSkippedForTheNextBrowserOfTheThread() {
        ProxyProvider provider = provider("a:1", "b:2");
        assertThat(provider.getProxyForCurrentThread().host()).isEqualTo("a");

        provider.markFailedForCurrentThread();

        assertThat(provider.getProxyForCurrentThread().host()).isEqualTo("b");
        provider.clearForCurrentThread();
        // round robin would give "a" again, but it is cooling down
        assertThat(provider.getProxyForCurrentThread().host()).isEqualTo("b");
    }

    @Test
    void everyProxyCoolingDownStillGivesOne() {
        ProxyProvider provider = provider("a:1");
        provider.getProxyForCurrentThread();
        provider.markFailedForCurrentThread();

        assertThat(provider.getProxyForCurrentThread().host()).isEqualTo("a");
    }

    @Test
    void noProxyConfigured() {
        assertThat(provider().getProxyForCurrentThread()).isNull();
    }

    @Test
    void detectsProxyErrors() {
        assertThat(Fetcher.isProxyFailure(new PlaywrightException(
                "net::ERR_PROXY_CONNECTION_FAILED at https://www.topcv.vn/x"))).isTrue();
        assertThat(Fetcher.isProxyFailure(new PlaywrightException(
                "net::ERR_TUNNEL_CONNECTION_FAILED at https://www.topcv.vn/x"))).isTrue();
        assertThat(Fetcher.isProxyFailure(new PlaywrightException(
                "net::ERR_NAME_NOT_RESOLVED at https://www.topcv.vn/x"))).isFalse();
    }
}
