package org.example.jobpostingcrawler.scheduler.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.example.jobpostingcrawler.scheduler.service.CrawlCacheService;
import org.example.jobpostingcrawler.storage.CrawledUrlService;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlCacheServiceImpl implements CrawlCacheService {

    private static final String SEEN_KEY_PREFIX = "crawler:url:seen:";
    private static final String BF_ADD = "BF.ADD";
    private static final String BF_EXISTS = "BF.EXISTS";

    private final StringRedisTemplate redisTemplate;
    private final CrawlerProperties properties;
    private final CrawledUrlService crawledUrlService;

    @Override
    public boolean isSeen(String url) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(seenKey(url)))) {
            return true;
        }
        return isSeenJob(url);
    }

    @Override
    public void markSeenNonJob(String url) {
        Duration ttl = properties.dedup().nonJobDedupTtl();
        redisTemplate.opsForValue().set(seenKey(url), "1", ttl);
        log.debug("(markSeenNonJob) url={}", url);
    }

    @Override
    public void markSeenJob(String url) {
        byte[] bloomKey = bloomFilterKey();
        byte[] value = bytes(url);
        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute(BF_ADD, bloomKey, value));
        log.debug("(markSeenJob) url={}", url);
    }

    private boolean isSeenJob(String url) {
        byte[] bloomKey = bloomFilterKey();
        byte[] value = bytes(url);
        Object result = redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute(BF_EXISTS, bloomKey, value));
        boolean possiblySeen = result instanceof Number number && number.longValue() == 1L;
        if (!possiblySeen) {
            return false;
        }

        return crawledUrlService.exists(url);
    }

    private byte[] bloomFilterKey() {
        return bytes(properties.dedup().jobBloomFilterName());
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String seenKey(String url) {
        return SEEN_KEY_PREFIX + url;
    }
}
