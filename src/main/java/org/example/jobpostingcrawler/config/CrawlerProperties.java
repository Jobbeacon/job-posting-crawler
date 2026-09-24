package org.example.jobpostingcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "crawler")
public record CrawlerProperties(
    SchedulerProperties scheduler,
    QueueProperties queue,
    WorkerProperties worker,
    RabbitProperties rabbitmq,
    List<SourceProperties> sources,
    GeocodeProperties geocode
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

  public record GeocodeProperties(
      boolean enabled,
      String baseUrl,
      String userAgent,
      Duration minInterval,
      Duration timeout,
      Duration positiveTtl,
      Duration negativeTtl
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
