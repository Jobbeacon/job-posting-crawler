package org.example.jobpostingcrawler.storage.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.entity.CrawledUrl;
import org.example.jobpostingcrawler.repository.CrawledUrlRepository;
import org.example.jobpostingcrawler.storage.CrawledUrlService;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawledUrlServiceImpl implements CrawledUrlService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CrawledUrlRepository crawledUrlRepository;

    @Override
    @Transactional
    public void save(String url, LocalDateTime expiredDate) {
        String urlHash = hash(url);
        Instant now = Instant.now();

        CrawledUrl crawledUrl = crawledUrlRepository.findByUrlHash(urlHash)
                .orElseGet(() -> CrawledUrl.builder()
                        .id(UUID.randomUUID())
                        .url(url)
                        .urlHash(urlHash)
                        .build());

        crawledUrl.setCrawledAt(now);
        crawledUrl.setExpiredAt(expiredDate != null ? expiredDate.atZone(VIETNAM_ZONE).toInstant() : null);

        crawledUrlRepository.save(crawledUrl);
        log.debug("(save) url={}", url);
    }

    @Override
    public boolean exists(String url) {
        return crawledUrlRepository.findByUrlHash(hash(url)).isPresent();
    }

    @Override
    public void forEachBatch(int batchSize, Consumer<List<String>> consumer) {
        // Postgres orders uuid bytewise, so the nil UUID sorts before every real id.
        UUID last = new UUID(0, 0);
        while (true) {
            List<CrawledUrl> batch = crawledUrlRepository.findByIdGreaterThanOrderById(last, Limit.of(batchSize));
            if (batch.isEmpty()) {
                return;
            }
            consumer.accept(batch.stream().map(CrawledUrl::getUrl).toList());
            last = batch.get(batch.size() - 1).getId();
        }
    }

    private String hash(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
