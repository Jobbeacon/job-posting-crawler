package org.example.jobpostingcrawler.extractor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.extractor.health.ExtractionHealthMonitor;
import org.example.jobpostingcrawler.extractor.html.HtmlJobPostingExtractor;
import org.example.jobpostingcrawler.extractor.jsonld.JsonLdJobPostingExtractor;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingExtractor {

    private static final int MAX_SKILL_LENGTH = 50;

    private final JsonLdJobPostingExtractor jsonLdExtractor;
    private final HtmlJobPostingExtractor htmlExtractor;
    private final ExtractionHealthMonitor healthMonitor;

    public JobPosting extract(Document doc, String url) {
        JobPosting jsonLd = jsonLdExtractor.extract(doc, url);
        HtmlJobPostingExtractor.HtmlExtraction htmlExtraction = htmlExtractor.extractDetailed(doc, url);
        JobPosting html = htmlExtraction == null ? null : htmlExtraction.posting();
        try {
            healthMonitor.record(htmlExtraction, jsonLd);
        } catch (Exception e) {
            log.warn("(extract) XPath health check failed for {}: {}", url, e.getMessage());
        }

        JobPosting merged = merge(jsonLd, html, url);
        log.debug("(extract) Sources for {}: jsonLd={}, html={}, skills={}",
                url, jsonLd != null, html != null, merged == null ? 0 : merged.getSkills().size());

        if (!isValid(merged, url)) {
            return null;
        }
        return merged;
    }

    private JobPosting merge(JobPosting primary, JobPosting fallback, String url) {
        if (primary == null && fallback == null) {
            return null;
        }
        if (primary == null) {
            return normalize(fallback, url);
        }
        if (fallback == null) {
            return normalize(primary, url);
        }

        JobPosting salarySource = primary.getMinSalary() != null || primary.getMaxSalary() != null
                ? primary
                : fallback;

        return JobPosting.builder()
                .url(url)
                .companyName(firstNonBlank(primary.getCompanyName(), fallback.getCompanyName()))
                .companyLogoUrl(firstNonBlank(primary.getCompanyLogoUrl(), fallback.getCompanyLogoUrl()))
                .title(firstNonBlank(primary.getTitle(), fallback.getTitle()))
                .description(firstNonBlank(primary.getDescription(), fallback.getDescription()))
                .experienceMonths(primary.getExperienceMonths() > 0
                        ? primary.getExperienceMonths()
                        : fallback.getExperienceMonths())
                .employmentType(firstNonBlank(primary.getEmploymentType(), fallback.getEmploymentType()))
                .postedDate(firstNonNull(primary.getPostedDate(), fallback.getPostedDate()))
                .expiredDate(firstNonNull(primary.getExpiredDate(), fallback.getExpiredDate()))
                .location(firstNonBlank(primary.getLocation(), fallback.getLocation()))
                .minSalary(salarySource.getMinSalary())
                .maxSalary(salarySource.getMaxSalary())
                .currency(salarySource.getCurrency())
                .salaryPeriod(salarySource.getSalaryPeriod())
                .industry(firstNonBlank(primary.getIndustry(), fallback.getIndustry()))
                .skills(mergeSkills(primary.getSkills(), fallback.getSkills()))
                .benefits(firstNonBlank(primary.getBenefits(), fallback.getBenefits()))
                .crawledAt(firstNonNull(primary.getCrawledAt(), fallback.getCrawledAt()))
                .build();
    }

    private JobPosting normalize(JobPosting source, String url) {
        source.setUrl(url);
        source.setSkills(mergeSkills(source.getSkills(), null));
        return source;
    }

    private List<String> mergeSkills(List<String> primary, List<String> fallback) {
        Map<String, String> unique = new LinkedHashMap<>();
        addSkills(unique, primary);
        addSkills(unique, fallback);
        return List.copyOf(unique.values());
    }

    private void addSkills(Map<String, String> unique, List<String> skills) {
        if (skills == null) {
            return;
        }
        for (String skill : skills) {
            if (skill == null) {
                continue;
            }
            String trimmed = skill.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_SKILL_LENGTH) {
                continue;
            }
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
    }

    private boolean isValid(JobPosting jobPosting, String url) {
        if (jobPosting == null) {
            log.warn("(isValid) No JobPosting extracted for URL {}", url);
            return false;
        }
        if (isBlank(jobPosting.getTitle())) {
            log.warn("(isValid) Title is missing for URL {}", url);
            return false;
        }
        if (isBlank(jobPosting.getDescription())) {
            log.warn("(isValid) Description is missing for URL {}", url);
            return false;
        }
        if (isBlank(jobPosting.getLocation())) {
            log.warn("(isValid) Location is missing for URL {}", url);
            return false;
        }
        return true;
    }

    private String firstNonBlank(String primary, String fallback) {
        return isBlank(primary) ? fallback : primary;
    }

    private LocalDateTime firstNonNull(LocalDateTime primary, LocalDateTime fallback) {
        return primary != null ? primary : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
