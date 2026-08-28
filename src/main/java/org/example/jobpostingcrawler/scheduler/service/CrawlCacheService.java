package org.example.jobpostingcrawler.scheduler.service;

public interface CrawlCacheService {

    boolean isSeen(String url);

    void markSeen(String url);
}
