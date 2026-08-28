package org.example.jobpostingcrawler.extractor;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class UrlExtractor {

    /**
     * Stub: extract outbound crawl URLs from a fetched page.
     */
    public List<String> extractUrls(Document document) {
        return List.of();
    }
}

