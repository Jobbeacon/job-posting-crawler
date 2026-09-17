package org.example.jobpostingcrawler.extractor;

import lombok.RequiredArgsConstructor;
import org.example.jobpostingcrawler.extractor.html.HtmlJobPostingExtractor;
import org.example.jobpostingcrawler.extractor.jsonld.JsonLdJobPostingExtractor;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingExtractor {

    private final JsonLdJobPostingExtractor jsonLdExtractor;
    private final HtmlJobPostingExtractor htmlExtractor;

    public JobPosting extract(Document doc, String url) {
        JobPosting jobPosting = jsonLdExtractor.extract(doc, url);
        if (validate(jobPosting, "JSON-LD")) {
            jobPosting = htmlExtractor.extract(doc, url);
        }

        if (validate(jobPosting, "HTML")) {
            return null;
        }

        return jobPosting;
    }

    private boolean validate(JobPosting jobPosting, String source) {
        if (jobPosting == null) {
            log.warn("[{}] Validation failed: JobPosting is null", source);
            return true;
        }
        if (isBlank(jobPosting.getTitle())) {
            log.warn("[{}] Validation failed: Title is missing for URL {}", source, jobPosting.getUrl());
            return true;
        }
        if (isBlank(jobPosting.getDescription())) {
            log.warn("[{}] Validation failed: Description is missing for URL {}", source, jobPosting.getUrl());
            return true;
        }
        if (isBlank(jobPosting.getLocation())) {
            log.warn("[{}] Validation failed: Location is missing for URL {}", source, jobPosting.getUrl());
            return true;
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
