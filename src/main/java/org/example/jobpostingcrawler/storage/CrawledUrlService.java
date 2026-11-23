package org.example.jobpostingcrawler.storage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

public interface CrawledUrlService {

    void save(String url, LocalDateTime expiredDate);

    boolean exists(String url);

    /** Hands every crawled url to {@code consumer}, in batches. */
    void forEachBatch(int batchSize, Consumer<List<String>> consumer);
}
