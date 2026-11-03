package org.example.jobpostingcrawler.extractor.jsonld;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class JsonLdJobPostingExtractor {

    private final ObjectMapper objectMapper;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern SALARY_NUMBER = Pattern.compile(
            "(\\d[\\d.,]*)(?:\\s*(triệu|tr|million|mil|m|k|nghìn|ngàn)(?!\\p{L}))?");
    private static final Pattern SALARY_DECIMAL = Pattern.compile("\\d+[.,]\\d{1,2}");
    private static final Pattern VND_SALARY = Pattern.compile(
            "₫|vnd|vnđ|đồng|\\d\\s*(?:đ|triệu|tr)(?!\\p{L})");
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
        List<Long> salaryRange = parseSalaryRange(salaryText);
        Long minSalary = salaryRange.isEmpty() ? null : salaryRange.get(0);
        Long maxSalary = salaryRange.size() < 2 ? (salaryRange.isEmpty() ? null : salaryRange.get(0)) : salaryRange.get(1);

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
                .location(buildLocation(jobPostingJson.path("jobLocation")))
                .minSalary(minSalary)
                .maxSalary(maxSalary)
                .currency(resolveCurrency(salaryText, text(salary, "currency")))
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

    private LocalDateTime parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(VIETNAM_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException exception) {
                try {
                    return LocalDateTime.ofInstant(Instant.parse(value), VIETNAM_ZONE);
                } catch (DateTimeParseException invalidDate) {
                    try {
                        return LocalDate.parse(value).atStartOfDay();
                    } catch (DateTimeParseException finalException) {
                        log.warn("Invalid job posting date: {}", value);
                        return null;
                    }
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
        Matcher matcher = SALARY_NUMBER.matcher(normalize(value));
        List<String> numbers = new ArrayList<>(2);
        List<Long> multipliers = new ArrayList<>(2);
        Long rangeMultiplier = null;
        while (matcher.find() && numbers.size() < 2) {
            Long multiplier = salaryMultiplier(matcher.group(2));
            numbers.add(matcher.group(1));
            multipliers.add(multiplier);
            if (multiplier != null) {
                rangeMultiplier = multiplier;
            }
        }

        List<Long> result = new ArrayList<>(2);
        for (int i = 0; i < numbers.size(); i++) {
            Long multiplier = multipliers.get(i) != null ? multipliers.get(i) : rangeMultiplier;
            try {
                result.add(multiplier == null
                        ? Long.parseLong(numbers.get(i).replace(",", "").replace(".", ""))
                        : parseScaledAmount(numbers.get(i), multiplier));
            } catch (NumberFormatException exception) {
                log.warn("Invalid salary value: {}", numbers.get(i));
            }
        }
        return result;
    }

    private Long salaryMultiplier(String unit) {
        if (unit == null) {
            return null;
        }
        return unit.startsWith("tr") || unit.startsWith("m") ? 1_000_000L : 1_000L;
    }

    private long parseScaledAmount(String number, long multiplier) {
        String plain = SALARY_DECIMAL.matcher(number).matches()
                ? number.replace(",", ".")
                : number.replace(",", "").replace(".", "");
        return new BigDecimal(plain).multiply(BigDecimal.valueOf(multiplier)).longValue();
    }

    private String resolveCurrency(String salaryText, String currency) {
        if (salaryText != null && VND_SALARY.matcher(normalize(salaryText)).find()) {
            return "VND";
        }
        return currency;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private String parseSalaryPeriod(String salaryText, String unitText) {
        if (salaryText != null) {
            String normalized = normalize(salaryText);
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
        if (node.isObject()) {
            return node.path("monthsOfExperience").asInt(0);
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
