package org.example.jobpostingcrawler.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.extractor.html.DomainConfigProvider;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
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
  private final DomainConfigProvider configProvider;

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
        // domain waiting for its first structure discovery: the coordinator pushes its seeds when done
        .filter(url -> {
          boolean paused = configProvider.isPaused(DomainConfigProvider.domainKey(url));
          if (paused) {
            log.info("(scheduleSeeds) skip {}: waiting for HTML structure discovery", url);
          }
          return !paused;
        })
        .map(url ->
            new CrawlUrl(url, 0)
        )
        .forEach(queueService::push);

    log.info("(scheduleSeeds) success");
  }
}
