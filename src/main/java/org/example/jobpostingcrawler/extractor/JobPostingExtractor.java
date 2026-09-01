package org.example.jobpostingcrawler.extractor;

import lombok.RequiredArgsConstructor;
import org.example.jobpostingcrawler.extractor.html.HtmlJobPostingExtractor;
import org.example.jobpostingcrawler.extractor.jsonld.JsonLdJobPostingExtractor;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobPostingExtractor {

    private final JsonLdJobPostingExtractor jsonLdExtractor;
    private final HtmlJobPostingExtractor htmlExtractor;

    public JobPosting extract(Document doc, String url) {
        JobPosting jobPosting = jsonLdExtractor.extract(doc, url);
        if (jobPosting != null) {
            return jobPosting;
        }
        return htmlExtractor.extract(doc, url);
    }
}
