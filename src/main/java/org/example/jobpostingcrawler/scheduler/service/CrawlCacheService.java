package org.example.jobpostingcrawler.scheduler.service;

public interface CrawlCacheService {

    boolean isSeen(String url);

    void markSeenNonJob(String url);

    void markSeenJob(String url);

    /**
     * Puts every url in {@code crawled_url} back into the Bloom filter. {@link #isSeen} only asks the database about
     * urls the filter knows, so after Redis loses its data every job would otherwise be crawled again.
     */
    void restoreSeenJobs();
}
