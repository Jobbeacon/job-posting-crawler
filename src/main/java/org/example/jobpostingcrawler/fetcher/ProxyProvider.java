package org.example.jobpostingcrawler.fetcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.PlaywrightProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyProvider {

    private final PlaywrightProperties properties;

    private final AtomicInteger nextIndex = new AtomicInteger();
    private final ThreadLocal<ProxyConfig> assignedProxy = new ThreadLocal<>();
    private final Map<ProxyConfig, Instant> cooldownUntil = new ConcurrentHashMap<>();

    public ProxyConfig getProxyForCurrentThread() {
        List<String> proxies = configuredProxies();
        if (proxies.isEmpty()) {
            return null;
        }

        ProxyConfig cached = assignedProxy.get();
        if (cached != null) {
            return cached;
        }

        ProxyConfig proxyConfig = pick(proxies);
        assignedProxy.set(proxyConfig);
        return proxyConfig;
    }

    /**
     * The current thread's proxy could not be reached: skip it for {@code playwright.proxy-cooldown} and drop it
     * from the thread, so the next browser of this thread gets another one.
     */
    public void markFailedForCurrentThread() {
        ProxyConfig current = assignedProxy.get();
        if (current == null) {
            return;
        }
        cooldownUntil.put(current, Instant.now().plus(properties.proxyCooldown()));
        assignedProxy.remove();
        log.warn("Proxy {}:{} unreachable, skipped for {}", current.host(), current.port(), properties.proxyCooldown());
    }

    public void clearForCurrentThread() {
        assignedProxy.remove();
    }

    // Round robin over the proxies not cooling down; when every proxy is cooling down, the next one is used anyway.
    private ProxyConfig pick(List<String> proxies) {
        Instant now = Instant.now();
        ProxyConfig fallback = null;
        for (int i = 0; i < proxies.size(); i++) {
            ProxyConfig candidate = ProxyConfig.parse(proxies.get(Math.floorMod(nextIndex.getAndIncrement(), proxies.size())));
            Instant until = cooldownUntil.get(candidate);
            if (until == null || !until.isAfter(now)) {
                cooldownUntil.remove(candidate);
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        log.warn("Every proxy is cooling down, using {}:{} anyway", fallback.host(), fallback.port());
        return fallback;
    }

    private List<String> configuredProxies() {
        return properties.proxies().stream().filter(proxy -> proxy != null && !proxy.isBlank()).toList();
    }
}
