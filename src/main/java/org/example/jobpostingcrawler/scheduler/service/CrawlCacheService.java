package org.example.jobpostingcrawler.scheduler.service;

public interface CrawlCacheService {

    boolean isSeen(String url);

    void markSeenNonJob(String url);

    void markSeenJob(String url);
}
