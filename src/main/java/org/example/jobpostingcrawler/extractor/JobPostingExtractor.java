package org.example.jobpostingcrawler.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.model.JobPosting;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingExtractor {
    private static final Pattern SALARY_NUMBER = Pattern.compile("\\d[\\d.,]*");

    public JobPosting parseJobPosting(JsonNode jobPostingJson, String url) {
        if (jobPostingJson == null || jobPostingJson.isNull()) {
            log.warn("Cannot parse a null job posting");
            return null;
        }

        JsonNode organization = jobPostingJson.path("hiringOrganization");
        JsonNode address = jobPostingJson.path("jobLocation").path("address");
        JsonNode salary = jobPostingJson.path("baseSalary");
        JsonNode salaryValue = salary.path("value");
        String salaryText = text(salaryValue, "value");
        List<Long> salaryRange = parseSalaryRange(salaryText);

        return JobPosting.builder()
                .url(url)
                .companyName(text(organization, "name"))
                .companyLogoUrl(text(organization, "logo"))
                .title(text(jobPostingJson, "title"))
                .description(text(jobPostingJson, "description"))
                .experienceMonths(parseExperienceMonths(jobPostingJson.path("experienceRequirements")))
                .employmentType(text(jobPostingJson, "employmentType"))
                .postedDate(parseDate(text(jobPostingJson, "datePosted")))
                .expiredDate(parseDate(text(jobPostingJson, "validThrough")))
                //TODO.province(firstNonBlank(text(address, "addressRegion"), text(address, "addressLocality")))
                //TODO.district(text(address, "addressLocality"))
                //TODO.street(text(address, "streetAddress"))
                .minSalary(salaryRange.isEmpty() ? null : salaryRange.get(0))
                .maxSalary(salaryRange.size() < 2 ? (salaryRange.isEmpty() ? null : salaryRange.get(0)) : salaryRange.get(1))
                .currency(text(salary, "currency"))
                .salaryPeriod(parseSalaryPeriod(salaryText, text(salaryValue, "unitText")))
                .industry(text(jobPostingJson, "industry"))
                .skills(parseSkills(jobPostingJson.path("skills")))
                .benefits(text(jobPostingJson, "jobBenefits"))
                .build();
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String result = value.asText().trim();
        return result.isEmpty() ? null : result;
    }

    private String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }

    private LocalDateTime parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException exception) {
                try {
                    return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
                } catch (DateTimeParseException invalidDate) {
                    log.warn("Invalid job posting date: {}", value);
                    return null;
                }
            }
        }
    }

    private List<String> parseSkills(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> skills = new ArrayList<>();
            node.forEach(item -> {
                String skill = item.asText().trim();
                if (!skill.isEmpty()) {
                    skills.add(skill);
                }
            });
            return skills;
        }
        return Arrays.stream(node.asText().split("[,;]"))
                .map(String::trim)
                .filter(skill -> !skill.isEmpty())
                .toList();
    }

    private List<Long> parseSalaryRange(String value) {
        if (value == null) {
            return List.of();
        }
        Matcher matcher = SALARY_NUMBER.matcher(value);
        List<Long> result = new ArrayList<>(2);
        while (matcher.find() && result.size() < 2) {
            String number = matcher.group().replace(",", "").replace(".", "");
            try {
                result.add(Long.parseLong(number));
            } catch (NumberFormatException exception) {
                log.warn("Invalid salary value: {}", matcher.group());
            }
        }
        return result;
    }

    private String parseSalaryPeriod(String salaryText, String unitText) {
        if (salaryText != null) {
            String normalized = salaryText.toLowerCase(Locale.ROOT);
            if (normalized.contains("/th\u00e1ng") || normalized.contains("/month")) {
                return "MONTH";
            }
            if (normalized.contains("/n\u0103m") || normalized.contains("/year")) {
                return "YEAR";
            }
            if (normalized.contains("/ng\u00e0y") || normalized.contains("/day")) {
                return "DAY";
            }
            if (normalized.contains("/gi\u1edd") || normalized.contains("/hour")) {
                return "HOUR";
            }
        }
        return unitText;
    }

    private int parseExperienceMonths(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(node.asText());
        if (!matcher.find()) {
            return 0;
        }
        int amount = Integer.parseInt(matcher.group());
        String normalized = node.asText().toLowerCase(Locale.ROOT);
        return normalized.contains("year") || normalized.contains("n\u0103m") ? amount * 12 : amount;
    }
}
