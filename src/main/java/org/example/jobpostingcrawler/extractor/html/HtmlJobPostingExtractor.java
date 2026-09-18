package org.example.jobpostingcrawler.extractor.html;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.config.HtmlExtractorProperties;
import org.example.jobpostingcrawler.model.JobPosting;
import org.example.jobpostingcrawler.utils.UrlUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.List;

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

        String domain = UrlUtils.extractDomain(url);
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
        String location = extractByXpath(doc, config.getLocation());
        String salary = extractByXpath(doc, config.getSalary());
        List<String> industryTexts = extractChildTexts(doc, config.getIndustry());
        String industry = industryTexts == null ? null : String.join(", ", industryTexts);
        List<String> skills = extractChildTexts(doc, config.getSkills());
        String benefits = extractByXpath(doc, config.getBenefits());

        return JobPosting.builder()
                .url(url)
                .title(title)
                .companyName(companyName)
                .companyLogoUrl(companyLogoUrl)
                .description(description)
                .employmentType(employmentType)
                .location(location)
                .industry(industry)
                .skills(skills)
                .benefits(benefits)
                .build();
    }

    private List<String> extractChildTexts(Document doc, String xpath) {
        String html = extractByXpath(doc, xpath);
        if (html == null || html.isEmpty()) {
            return null;
        }

        Element container = Jsoup.parseBodyFragment(html).body();
        Elements tags = container.children();
        if (tags.isEmpty()) {
            return null;
        }

        List<String> texts = tags.stream()
                .map(Element::text)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList();

        return texts.isEmpty() ? null : texts;
    }

    private String extractByXpath(Document doc, String xpath) {
        if (xpath == null || xpath.isEmpty()) {
            return null;
        }
        log.debug("Evaluating xpath: {}", xpath);
        if (xpath.endsWith("/text()")) {
            log.warn("Warning: Jsoup selectXpath does not support /text() at the end. Please remove it and select the element itself.");
        }
        try {
            if (xpath.contains("/@")) {
                int idx = xpath.lastIndexOf("/@");
                String elementXpath = xpath.substring(0, idx);
                String attrName = xpath.substring(idx + 2);
                log.debug("Attribute extraction detected. Element XPath: {}, Attribute: {}", elementXpath, attrName);
                Elements elements = doc.selectXpath(elementXpath);
                log.debug("Found {} elements for Element XPath: {}", elements.size(), elementXpath);
                if (!elements.isEmpty()) {
                    String value = elements.attr(attrName).trim();
                    log.debug("Extracted attribute value: {}", value);
                    return value;
                }
                return null;
            }

            Elements elements = doc.selectXpath(xpath);
            log.info("Found {} elements for XPath: {}", elements.size(), xpath);
            if (!elements.isEmpty()) {
                String value = String.join("|", elements.eachText()).trim();
                log.info("Extracted html value: {}", value.length() > 200 ? value.substring(0, 200) + "..." : value);
                return value;
            }
        } catch (Exception e) {
            log.warn("Error evaluating xpath: {}", xpath, e);
        }
        return null;
    }
}
