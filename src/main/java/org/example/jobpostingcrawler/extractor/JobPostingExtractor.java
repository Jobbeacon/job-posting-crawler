package org.example.jobpostingcrawler.extractor;

import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;

public abstract class JobPostingExtractor {
    public abstract JobPosting extract(Document doc, String url);
}
