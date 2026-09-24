package org.example.jobpostingcrawler.scheduler.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.model.CrawlUrl;
import org.example.jobpostingcrawler.scheduler.service.CrawlQueueService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CrawlQueueServiceImpl implements CrawlQueueService {
  private final StringRedisTemplate redisTemplate;

  private static final String PENDING_KEY = "crawler:url:pending";

  @Override
  public boolean push(CrawlUrl crawlUrl) {
    log.debug("(push) push url to redis: {}", crawlUrl.url());

    Boolean added = redisTemplate.opsForZSet()
        .add(PENDING_KEY, crawlUrl.url(), crawlUrl.depth());

    return Boolean.TRUE.equals(added);
  }

  @Override
  public long pushAll(Collection<CrawlUrl> crawlUrls) {
    log.debug("(pushAll) url size: {}", crawlUrls.size());

    Set<ZSetOperations.TypedTuple<String>> tuples =
        crawlUrls.stream()
            .map(crawlUrl -> {
              return ZSetOperations.TypedTuple.of(
                  crawlUrl.url(),
                  (double) crawlUrl.depth()
              );
            })
            .collect(Collectors.toSet());

    Long added = redisTemplate.opsForZSet().add(PENDING_KEY, tuples);

    return added != null ? added : 0;
  }

  @Override
  public Optional<CrawlUrl> pop() {
    Set<ZSetOperations.TypedTuple<String>> result = redisTemplate.opsForZSet().popMin(PENDING_KEY, 1);

    if (result == null || result.isEmpty()) {
      return Optional.empty();
    }

    ZSetOperations.TypedTuple<String> tuple = result.iterator().next();

    log.debug("(pop) url: {}", tuple.getValue());

    return Optional.of(
        new CrawlUrl(
            tuple.getValue(),
            Objects.requireNonNull(tuple.getScore()).intValue()
        )
    );
  }

  @Override
  public long size() {
    Long size = redisTemplate.opsForZSet().zCard(PENDING_KEY);
    return size != null ? size : 0;
  }
}
