package org.example.jobpostingcrawler.storage;

import java.time.LocalDateTime;

public interface CrawledUrlService {

    void save(String url, LocalDateTime expiredDate);
}
