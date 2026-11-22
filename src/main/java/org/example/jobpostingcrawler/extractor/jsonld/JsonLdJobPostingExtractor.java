package org.example.jobpostingcrawler.extractor.jsonld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.extractor.JobFieldParser;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class JsonLdJobPostingExtractor {

    private final ObjectMapper objectMapper;
    private static final Set<String> BLANK_TEXT_VALUES = Set.of("not available", "n/a", "na");

    public JobPosting extract(Document doc, String url) {
        if (doc == null) {
            log.warn("Cannot extract from a null document");
            return null;
        }

        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                if ("JobPosting".equals(node.path("@type").asText())) {
                    return parseJobPosting(node, url);
                }
            } catch (Exception e) {
                log.warn("Invalid JSON in script tag, continue ...");
            }
        }
        return null;
    }

    private JobPosting parseJobPosting(JsonNode jobPostingJson, String url) {
        if (jobPostingJson == null || jobPostingJson.isNull()) {
            log.warn("Cannot parse a null job posting");
            return null;
        }

        JsonNode organization = jobPostingJson.path("hiringOrganization");
        JsonNode salary = jobPostingJson.path("baseSalary");
        JsonNode salaryValue = salary.path("value");
        String salaryText = text(salaryValue, "value");
        JobFieldParser.Salary parsedSalary = JobFieldParser.parseSalary(
                salaryText, text(salary, "currency"), text(salaryValue, "unitText"));
        Long minSalary = parsedSalary.min();
        Long maxSalary = parsedSalary.max();

        if (minSalary == null && maxSalary == null) {
            JsonNode minNode = salaryValue.path("minValue");
            if (!minNode.isMissingNode() && !minNode.isNull()) {
                minSalary = minNode.asLong();
            }
            JsonNode maxNode = salaryValue.path("maxValue");
            if (!maxNode.isMissingNode() && !maxNode.isNull()) {
                maxSalary = maxNode.asLong();
            }
        }

        // "Thương lượng" pages still declare a currency/unit (VietnamWorks: USD per HOUR): meaningless without amount
        boolean hasSalary = minSalary != null || maxSalary != null;

        return JobPosting.builder()
                .url(url)
                .companyName(text(organization, "name"))
                .companyLogoUrl(text(organization, "logo"))
                .title(text(jobPostingJson, "title"))
                .description(text(jobPostingJson, "description"))
                .experienceMonths(parseExperienceMonths(jobPostingJson.path("experienceRequirements")))
                .employmentType(text(jobPostingJson, "employmentType"))
                .postedDate(JobFieldParser.parseDate(text(jobPostingJson, "datePosted")))
                .expiredDate(JobFieldParser.parseDate(text(jobPostingJson, "validThrough")))
                .location(buildLocation(jobPostingJson.path("jobLocation")))
                .minSalary(minSalary)
                .maxSalary(maxSalary)
                .currency(hasSalary ? parsedSalary.currency() : null)
                .salaryPeriod(hasSalary ? parsedSalary.period() : null)
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
        if (result.isEmpty() || BLANK_TEXT_VALUES.contains(result.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return result;
    }

    private String buildLocation(JsonNode jobLocationNode) {
        if (jobLocationNode.isMissingNode() || jobLocationNode.isNull()) {
            return null;
        }

        List<JsonNode> places = new ArrayList<>();
        if (jobLocationNode.isArray()) {
            jobLocationNode.forEach(places::add);
        } else {
            places.add(jobLocationNode);
        }

        List<String> locations = new ArrayList<>();
        for (JsonNode place : places) {
            JsonNode address = place.path("address");
            String location = joinLocation(
                    text(address, "streetAddress"),
                    text(address, "addressLocality"),
                    text(address, "addressRegion"));
            if (location != null && !locations.contains(location)) {
                locations.add(location);
            }
        }

        return locations.isEmpty() ? null : String.join("|", locations);
    }

    private String joinLocation(String street, String district, String province) {
        List<String> parts = new ArrayList<>();
        Set<String> joinedWords = new HashSet<>();

        for (String value : new String[]{street, district, province}) {
            if (value == null || value.isBlank()) {
                continue;
            }

            Set<String> words = new HashSet<>(Arrays.asList(
                    value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
            words.remove("");
            if (words.isEmpty()) {
                continue;
            }

            if (joinedWords.stream().noneMatch(words::contains)) {
                parts.add(value);
                joinedWords.addAll(words);
            }
        }

        return parts.isEmpty() ? null : String.join(", ", parts);
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

    private int parseExperienceMonths(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (node.isObject()) {
            return node.path("monthsOfExperience").asInt(0);
        }
        return JobFieldParser.parseExperienceMonths(node.asText());
    }

}
