package org.example.jobpostingcrawler.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XPaths of one domain used by the HTML extractor. Stored as the JSON object
 * {@code domain_extractor_config.xpaths}, keyed by the component names below ("title", "companyName"...).
 * A blank/missing XPath means the field is not extracted from HTML.
 */
public record ExtractorConfig(
        String title,
        String companyName,
        String companyLogoUrl,
        String description,
        String experienceMonths,
        String employmentType,
        String postedDate,
        String expiredDate,
        String location,
        String salary,
        String industry,
        String skills,
        String benefits
) {

    public static ExtractorConfig fromMap(Map<String, String> xpaths) {
        Map<String, String> x = xpaths == null ? Map.of() : xpaths;
        return new ExtractorConfig(
                x.get("title"),
                x.get("companyName"),
                x.get("companyLogoUrl"),
                x.get("description"),
                x.get("experienceMonths"),
                x.get("employmentType"),
                x.get("postedDate"),
                x.get("expiredDate"),
                x.get("location"),
                x.get("salary"),
                x.get("industry"),
                x.get("skills"),
                x.get("benefits"));
    }

    /** Non-blank XPaths only, in field order. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        put(map, "title", title);
        put(map, "companyName", companyName);
        put(map, "companyLogoUrl", companyLogoUrl);
        put(map, "description", description);
        put(map, "experienceMonths", experienceMonths);
        put(map, "employmentType", employmentType);
        put(map, "postedDate", postedDate);
        put(map, "expiredDate", expiredDate);
        put(map, "location", location);
        put(map, "salary", salary);
        put(map, "industry", industry);
        put(map, "skills", skills);
        put(map, "benefits", benefits);
        return map;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
