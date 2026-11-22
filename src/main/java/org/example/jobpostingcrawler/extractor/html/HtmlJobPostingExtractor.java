package org.example.jobpostingcrawler.extractor.html;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.extractor.JobFieldParser;
import org.example.jobpostingcrawler.model.ExtractorConfig;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HtmlJobPostingExtractor {
    private final DomainConfigProvider configProvider;

    /**
     * Result of the HTML extraction of one page.
     *
     * @param domainKey domain_extractor_config key of the page
     * @param posting   typed values
     * @param raw       text found by each configured XPath (null when nothing matched), keyed like
     *                  ExtractorConfig ("title", "salary"...); list fields hold a {@code List<String>}. Used to
     *                  watch the health of the XPaths.
     */
    public record HtmlExtraction(String domainKey, JobPosting posting, Map<String, Object> raw) {
    }

    public JobPosting extract(Document doc, String url) {
        HtmlExtraction extraction = extractDetailed(doc, url);
        return extraction == null ? null : extraction.posting();
    }

    /** Same as {@link #extract} with the raw values; null when the domain has no XPath. */
    public HtmlExtraction extractDetailed(Document doc, String url) {
        if (doc == null || url == null) {
            log.warn("Cannot parse a null document or url");
            return null;
        }

        String domainKey = DomainConfigProvider.domainKey(url);
        if (domainKey == null) {
            log.warn("Cannot extract domain from url: {}", url);
            return null;
        }

        ExtractorConfig config = configProvider.activeConfig(domainKey).orElse(null);
        if (config == null) {
            // normal while discovery has not produced XPaths for the domain: JSON-LD alone is used
            log.debug("No active HTML configuration for domain key: {}", domainKey);
            return null;
        }

        String title = extractByXpath(doc, config.title());
        String companyName = extractByXpath(doc, config.companyName());
        String companyLogoUrl = extractByXpath(doc, config.companyLogoUrl());
        String description = extractByXpath(doc, config.description());
        String experienceText = extractByXpath(doc, config.experienceMonths());
        String employmentType = extractByXpath(doc, config.employmentType());
        String postedDateText = extractByXpath(doc, config.postedDate());
        String expiredDateText = extractByXpath(doc, config.expiredDate());
        String location = extractByXpath(doc, config.location());
        String salaryText = extractByXpath(doc, config.salary());
        List<String> industryTexts = extractChildTexts(doc, config.industry());
        String industry = industryTexts == null ? null : String.join(", ", industryTexts);
        List<String> skills = extractChildTexts(doc, config.skills());
        String benefits = extractByXpath(doc, config.benefits());

        // Raw page texts ("2 năm", "Hạn nộp: 29/11/2026", "20 - 35 triệu") -> typed values, same rules as JSON-LD
        JobFieldParser.Salary salary = JobFieldParser.parseSalary(salaryText, null, null);

        Map<String, Object> raw = new LinkedHashMap<>();
        putIfConfigured(raw, "title", config.title(), title);
        putIfConfigured(raw, "companyName", config.companyName(), companyName);
        putIfConfigured(raw, "companyLogoUrl", config.companyLogoUrl(), companyLogoUrl);
        putIfConfigured(raw, "description", config.description(), description);
        putIfConfigured(raw, "experienceMonths", config.experienceMonths(), experienceText);
        putIfConfigured(raw, "employmentType", config.employmentType(), employmentType);
        putIfConfigured(raw, "postedDate", config.postedDate(), postedDateText);
        putIfConfigured(raw, "expiredDate", config.expiredDate(), expiredDateText);
        putIfConfigured(raw, "location", config.location(), location);
        putIfConfigured(raw, "salary", config.salary(), salaryText);
        putIfConfigured(raw, "industry", config.industry(), industryTexts);
        putIfConfigured(raw, "skills", config.skills(), skills);
        putIfConfigured(raw, "benefits", config.benefits(), benefits);

        JobPosting posting = JobPosting.builder()
                .url(url)
                .title(title)
                .companyName(companyName)
                .companyLogoUrl(companyLogoUrl)
                .description(description)
                .experienceMonths(JobFieldParser.parseExperienceMonths(experienceText))
                .employmentType(employmentType)
                .postedDate(JobFieldParser.parseDate(postedDateText))
                .expiredDate(JobFieldParser.parseDeadline(expiredDateText))
                .location(location)
                .minSalary(salary.min())
                .maxSalary(salary.max())
                .currency(salary.min() != null ? salary.currency() : null)
                .salaryPeriod(salary.min() != null ? salary.period() : null)
                .industry(industry)
                .skills(skills)
                .benefits(benefits)
                .build();
        return new HtmlExtraction(domainKey, posting, raw);
    }

    private static void putIfConfigured(Map<String, Object> raw, String field, String xpath, Object value) {
        if (xpath != null && !xpath.isBlank()) {
            raw.put(field, value);
        }
    }

    /**
     * The xpath points to the element wrapping a list (skills tags, industries...); each direct child is one
     * item. Must work on the matched element itself: going through its text would lose the child tags.
     */
    private List<String> extractChildTexts(Document doc, String xpath) {
        if (xpath == null || xpath.isEmpty()) {
            return null;
        }

        Elements containers;
        try {
            containers = doc.selectXpath(xpath);
        } catch (Exception e) {
            log.warn("Error evaluating xpath: {}", xpath, e);
            return null;
        }
        if (containers.isEmpty()) {
            return null;
        }

        Element container = containers.first();
        Elements tags = container.children();
        if (tags.isEmpty()) {
            // plain text list without tags (VietnamWorks: <p>QA, Training, Coaching</p>): split on commas
            List<String> items = Arrays.stream(container.text().split(","))
                    .map(String::trim)
                    .filter(text -> !text.isEmpty())
                    .toList();
            return items.isEmpty() ? null : items;
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
            log.debug("Found {} elements for XPath: {}", elements.size(), xpath);
            if (!elements.isEmpty()) {
                String value = String.join("|", elements.eachText()).trim();
                log.debug("Extracted html value: {}", value.length() > 200 ? value.substring(0, 200) + "..." : value);
                return value;
            }
        } catch (Exception e) {
            log.warn("Error evaluating xpath: {}", xpath, e);
        }
        return null;
    }
}
