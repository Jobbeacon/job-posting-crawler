package org.example.jobpostingcrawler.model;

public record CrawlUrl(
    String url,
    int depth
) {}
