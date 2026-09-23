package org.example.jobpostingcrawler.scheduler.service.impl;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.BooleanOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlCacheServiceImpl implements CrawlCacheService {

    private static final String SEEN_KEY_PREFIX = "crawler:url:seen:";
    private static final Duration BLOOM_COMMAND_TIMEOUT = Duration.ofSeconds(5);

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
        executeBloomCommand(BloomCommand.BF_ADD, url);
        log.debug("(markSeenJob) url={}", url);
    }

    private boolean isSeenJob(String url) {
        boolean possiblySeen = Boolean.TRUE.equals(executeBloomCommand(BloomCommand.BF_EXISTS, url));
        if (!possiblySeen) {
            return false;
        }

        return crawledUrlService.exists(url);
    }

    private Boolean executeBloomCommand(BloomCommand command, String url) {
        return redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            Object nativeConnection = connection.getNativeConnection();
            if (!(nativeConnection instanceof BaseRedisAsyncCommands<?, ?> asyncCommands)) {
                throw new IllegalStateException("Bloom filter commands require a Lettuce Redis connection");
            }

            @SuppressWarnings("unchecked")
            BaseRedisAsyncCommands<byte[], byte[]> commands = (BaseRedisAsyncCommands<byte[], byte[]>) asyncCommands;

            CommandArgs<byte[], byte[]> args = new CommandArgs<>(ByteArrayCodec.INSTANCE)
                    .addKey(bloomFilterKey())
                    .addValue(bytes(url));

            RedisFuture<Boolean> future = commands.dispatch(command, new BooleanOutput<>(ByteArrayCodec.INSTANCE), args);
            try {
                return future.get(BLOOM_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + command + " on url=" + url, e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException(command + " failed for url=" + url, e);
            }
        });
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

    private enum BloomCommand implements ProtocolKeyword {
        BF_ADD("BF.ADD"),
        BF_EXISTS("BF.EXISTS");

        private final byte[] bytes;

        BloomCommand(String command) {
            this.bytes = command.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public String toString() {
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }
}
