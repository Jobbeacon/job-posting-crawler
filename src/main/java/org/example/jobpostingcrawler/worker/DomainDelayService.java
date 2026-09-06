package org.example.jobpostingcrawler.worker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
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
      if (!source.enabled() || source.delay() == null) continue;

      long delayMs = source.delay().toMillis();

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

  public void await(String url) {
    String domain = UrlUtils.extractDomain(url);

    if (Objects.isNull(domain)) return;

    Long delayMs = domainDelayMap.get(domain);
    if (Objects.isNull(delayMs) || delayMs <= 0) return;

    Object lock = domainLocks.computeIfAbsent(domain, key -> new Object());
    long waitMs = 0;

    synchronized (lock) {
      long now = System.currentTimeMillis();
      long nextAllowed = nextAllowedTimeMap.getOrDefault(domain, 0L);

      long scheduleTime = Math.max(now, nextAllowed);
      nextAllowedTimeMap.put(domain, scheduleTime + delayMs);

      waitMs = scheduleTime - now;
    }

    if (waitMs > 0) {
      log.info("(await) Domain [{}] delay active. Thread sleep for [{}]",
          domain, waitMs);

      try {
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while waiting for domain [{}] delay", domain);
      }
    }
  }

  private void registerDomainDelay(String url, long delayMs) {
    String domain = UrlUtils.extractDomain(url);
    if (domain != null) {
      domainDelayMap.put(domain, delayMs);
    }
  }
}
