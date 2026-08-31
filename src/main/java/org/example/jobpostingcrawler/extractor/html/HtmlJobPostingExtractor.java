package org.example.jobpostingcrawler.extractor.html;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.HtmlExtractorProperties;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class HtmlJobPostingExtractor {
    private final HtmlExtractorProperties properties;

    public JobPosting extract(Document doc, String url) {
        if (doc == null || url == null) {
            log.warn("Cannot parse a null document or url");
            return null;
        }

        String domain = extractDomain(url);
        if (domain == null) {
            log.warn("Cannot extract domain from url: {}", url);
            return null;
        }
        
        String domainKey = domain.replace(".", "_");
        if (properties.getDomains() == null || !properties.getDomains().containsKey(domainKey)) {
            log.warn("No HTML configuration found for domain key: {}", domainKey);
            return null;
        }

        org.example.jobpostingcrawler.config.HtmlExtractorProperties.DomainConfig config = properties.getDomains().get(domainKey);

        String title = extractByXpath(doc, config.getTitle());
        String companyName = extractByXpath(doc, config.getCompanyName());
        String companyLogoUrl = extractByXpath(doc, config.getCompanyLogoUrl());
        String description = extractByXpath(doc, config.getDescription());
        String experienceMonths = extractByXpath(doc, config.getExperienceMonths());
        String employmentType = extractByXpath(doc, config.getEmploymentType());
        String postedDate = extractByXpath(doc, config.getPostedDate());
        String expiredDate = extractByXpath(doc, config.getExpiredDate());
        String industry = extractByXpath(doc, config.getIndustry());
        String skills = extractByXpath(doc, config.getSkills());
        String benefits = extractByXpath(doc, config.getBenefits());

        return JobPosting.builder()
                .url(url)
                .title(title)
                .companyName(companyName)
                .companyLogoUrl(companyLogoUrl)
                .description(description)
                .employmentType(employmentType)
                .industry(industry)
                .benefits(benefits)
                .build();
    }

    private String extractDomain(String url) {
        try {
            URI uri = new URI(url);
            String domain = uri.getHost();
            return domain != null ? domain.startsWith("www.") ? domain.substring(4) : domain : null;
        } catch (Exception e) {
            log.warn("Invalid url: {}", url);
            return null;
        }
    }

    private String extractByXpath(Document doc, String xpath) {
        if (xpath == null || xpath.isEmpty()) {
            return null;
        }
        try {
            if (xpath.contains("/@")) {
                int idx = xpath.lastIndexOf("/@");
                String elementXpath = xpath.substring(0, idx);
                String attrName = xpath.substring(idx + 2);
                Elements elements = doc.selectXpath(elementXpath);
                if (!elements.isEmpty()) {
                    return elements.attr(attrName).trim();
                }
                return null;
            }

            Elements elements = doc.selectXpath(xpath);
            if (!elements.isEmpty()) {
                return elements.html().trim();
            }
        } catch (Exception e) {
            log.warn("Error evaluating xpath: {}", xpath, e);
        }
        return null;
    }

    private Long parseLong(String val) {
        if (val == null || val.isEmpty()) return null;
        try {
            // Lọc chỉ lấy số
            String num = val.replaceAll("[^0-9]", "");
            if (num.isEmpty()) return null;
            return Long.parseLong(num);
        } catch (Exception e) {
            return null;
        }
    }
}
