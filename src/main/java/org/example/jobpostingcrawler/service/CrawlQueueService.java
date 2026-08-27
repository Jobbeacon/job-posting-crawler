package org.example.jobpostingcrawler.service;

import org.example.jobpostingcrawler.model.CrawlUrl;

import java.util.Collection;
import java.util.Optional;

public interface CrawlQueueService {

  boolean push(CrawlUrl url);

  long pushAll(Collection<CrawlUrl> urls);

  Optional<CrawlUrl> pop();
}
