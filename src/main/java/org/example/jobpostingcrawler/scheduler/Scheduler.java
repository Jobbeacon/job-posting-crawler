package org.example.jobpostingcrawler.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.service.CrawlQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "crawler.scheduler",
    name = "enabled",
    havingValue = "true"
)
@Slf4j
public class Scheduler {

  private final CrawlerProperties properties;
  private final CrawlQueueService queueService;

  @Scheduled(
      fixedDelayString =
          "${crawler.scheduler.seed-interval:30m}"
  )
  public void scheduleSeeds() {
    log.info("(scheduleSeeds) start");

    properties.sources()
        .stream()
        .filter(
            CrawlerProperties.SourceProperties::enabled
        )
        .flatMap(source ->
            source.seeds().stream()
        )
        .map(url ->
            new CrawlUrl(url, 0)
        )
        .forEach(queueService::push);

    log.info("(scheduleSeeds) success");
  }
}
