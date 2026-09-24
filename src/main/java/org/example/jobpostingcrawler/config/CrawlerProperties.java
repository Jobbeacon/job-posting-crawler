package org.example.jobpostingcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "crawler")
public record CrawlerProperties(
    SchedulerProperties scheduler,
    DedupProperties dedup,
    WorkerProperties worker,
    RabbitProperties rabbitmq,
    List<SourceProperties> sources
) {

  public record SchedulerProperties(
      boolean enabled,
      Duration seedInterval
  ) {
  }

  public record DedupProperties(
      Duration nonJobDedupTtl,
      String jobBloomFilterName
  ) {
  }

  public record WorkerProperties(
      boolean enabled,
      int poolSize,
      Duration idleInterval,
      int maxDepth
  ) {
  }

  public record RabbitProperties(
      String exchange,
      String queue,
      String routingKey
  ) {
  }

  public record SourceProperties(
      String name,
      boolean enabled,
      List<String> seeds,
      String jobPageUrlPrefix,
      Duration delay
  ) {
  }
}
