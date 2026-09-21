package org.example.jobpostingcrawler.scheduler.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.scheduler.service.CrawlCacheService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlCacheServiceImpl implements CrawlCacheService {

    private static final String SEEN_KEY_PREFIX = "crawler:url:seen:";

    private final StringRedisTemplate redisTemplate;
    private final CrawlerProperties properties;

    @Override
    public boolean isSeen(String url) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(seenKey(url)));
    }

    @Override
    public void markSeenNonJob(String url) {
        Duration ttl = properties.queue().nonJobDedupTtl();
        redisTemplate.opsForValue().set(seenKey(url), "1", ttl);
        log.debug("(markSeenNonJob) url={}", url);
    }

    @Override
    public void markSeenJob(String url) {
        Duration ttl = properties.queue().jobDedupTtl();
        redisTemplate.opsForValue().set(seenKey(url), "1", ttl);
        log.debug("(markSeenJob) url={}", url);
    }

    private String seenKey(String url) {
        return SEEN_KEY_PREFIX + url;
    }
}
