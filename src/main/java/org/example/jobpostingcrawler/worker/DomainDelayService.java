package org.example.jobpostingcrawler.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.fetcher.ProxyConfig;
import org.example.jobpostingcrawler.fetcher.ProxyProvider;
import org.example.jobpostingcrawler.utils.UrlUtils;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DomainDelayService {

  private final CrawlerProperties properties;
  private final ProxyProvider proxyProvider;

  private final Map<String, Long> domainDelayMap = new ConcurrentHashMap<>();

  private final Map<String, Object> domainLocks = new ConcurrentHashMap<>();

  private final Map<String, Long> nextAllowedTimeMap = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    if (Objects.isNull(properties)
        || Objects.isNull(properties.sources())
        || properties.sources().isEmpty()
    ) {
      return;
    }

    for (CrawlerProperties.SourceProperties source : properties.sources()) {
      if (!source.enabled()) continue;

      long delayMs = source.delay() != null ? source.delay().toMillis() : 0L;


      if (source.seeds() != null && !source.seeds().isEmpty()) {
        for (String seed : source.seeds()) {
          this.registerDomainDelay(seed, delayMs);
        }
      }

      if (source.jobPageUrlPrefix() != null &&  !source.jobPageUrlPrefix().isEmpty()) {
        this.registerDomainDelay(source.jobPageUrlPrefix(), delayMs);
      }
    }

    log.info("Initialized DomainDelayService with domain delays: {}", domainDelayMap);
  }

  public void await(String url) throws InterruptedException {
    String domain = UrlUtils.extractDomain(url);

    if (Objects.isNull(domain)) return;

    Long delayMs = domainDelayMap.get(domain);
    if (Objects.isNull(delayMs) || delayMs <= 0) return;

    String proxyKey = proxyKey(proxyProvider.getProxyForCurrentThread());
    String key = domain + "|" + proxyKey;

    Object lock = domainLocks.computeIfAbsent(key, k -> new Object());
    long waitMs = 0;

    synchronized (lock) {
      long now = System.currentTimeMillis();
      long nextAllowed = nextAllowedTimeMap.getOrDefault(key, 0L);

      long scheduleTime = Math.max(now, nextAllowed);
      nextAllowedTimeMap.put(key, scheduleTime + delayMs);

      waitMs = scheduleTime - now;
    }

    if (waitMs > 0) {
      log.info("(await) Domain [{}] via proxy [{}] delay active. Thread sleep for [{}]",
          domain, proxyKey, waitMs);
      Thread.sleep(waitMs);
    }
  }

  private String proxyKey(ProxyConfig proxyConfig) {
    return proxyConfig != null ? proxyConfig.host() + ":" + proxyConfig.port() : "direct";
  }

  private void registerDomainDelay(String url, long delayMs) {
    String domain = UrlUtils.extractDomain(url);
    if (domain != null) {
      domainDelayMap.put(domain, delayMs);
    }
  }
}
