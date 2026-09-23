package org.example.jobpostingcrawler.fetcher;

import lombok.RequiredArgsConstructor;
import org.example.jobpostingcrawler.config.PlaywrightProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class ProxyProvider {

    private final PlaywrightProperties properties;

    private final AtomicInteger nextIndex = new AtomicInteger();
    private final ThreadLocal<ProxyConfig> assignedProxy = new ThreadLocal<>();

    public ProxyConfig getProxyForCurrentThread() {
        List<String> proxies = configuredProxies();
        if (proxies.isEmpty()) {
            return null;
        }

        ProxyConfig cached = assignedProxy.get();
        if (cached != null) {
            return cached;
        }

        int index = Math.floorMod(nextIndex.getAndIncrement(), proxies.size());
        ProxyConfig proxyConfig = ProxyConfig.parse(proxies.get(index));
        assignedProxy.set(proxyConfig);
        return proxyConfig;
    }

    public void clearForCurrentThread() {
        assignedProxy.remove();
    }

    private List<String> configuredProxies() {
        List<String> proxies = properties.proxies();
        if (proxies == null) {
            return List.of();
        }
        return proxies.stream().filter(proxy -> proxy != null && !proxy.isBlank()).toList();
    }
}
