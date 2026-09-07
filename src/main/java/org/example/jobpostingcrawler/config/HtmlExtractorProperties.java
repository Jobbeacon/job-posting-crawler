package org.example.jobpostingcrawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "crawler.extractor.html")
public class HtmlExtractorProperties {
    private Map<String, DomainConfig> domains;

    @Data
    public static class DomainConfig {
        private String title;
        private String companyName;
        private String companyLogoUrl;
        private String description;
        private String experienceMonths;
        private String employmentType;
        private String postedDate;
        private String expiredDate;
        private String location;
        private String salary;
        private String industry;
        private String skills;
        private String benefits;
    }
}
