package org.example.jobpostingcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param settleTime     wait after DOMCONTENTLOADED so the page's scripts can fill the DOM
 * @param blockResources abort image, media and font requests: the crawler only reads the HTML
 * @param proxyCooldown  how long a proxy that failed to connect is skipped when a thread picks a new one
 */
@ConfigurationProperties(prefix = "playwright")
public record PlaywrightProperties(
    boolean headless,
    List<String> proxies,
    Duration settleTime,
    Boolean blockResources,
    Duration proxyCooldown
) {

  public PlaywrightProperties {
    if (proxies == null) {
      proxies = List.of();
    }
    if (settleTime == null) {
      settleTime = Duration.ofSeconds(10);
    }
    if (blockResources == null) {
      blockResources = true;
    }
    if (proxyCooldown == null) {
      proxyCooldown = Duration.ofMinutes(5);
    }
  }
}
