package org.example.jobpostingcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "crawler")
public record CrawlerProperties(
    SchedulerProperties scheduler,
    QueueProperties queue,
    List<SourceProperties> sources
) {

  public record SchedulerProperties(
      boolean enabled,
      Duration seedInterval
  ) {
  }

  public record QueueProperties(
      Duration dedupTtl
  ) {
  }

  public record SourceProperties(
      String name,
      boolean enabled,
      List<String> seeds
  ) {
  }
}
