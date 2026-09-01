package org.example.jobpostingcrawler.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class JobPosting {
    private String url;
    private String companyName;
    private String companyLogoUrl;
    private String title;
    private String description;
    private int experienceMonths;
    private String employmentType;
    private LocalDateTime postedDate;
    private LocalDateTime expiredDate;
    private String province;
    private String district;
    private String street;
    private Long minSalary;
    private Long maxSalary;
    private String currency;
    private String salaryPeriod;
    private String industry;
    private List<String> skills;
    private String benefits;

    @Override
    public String toString() {
        return """
            JobPosting {
              url=%s
              companyName=%s
              companyLogoUrl=%s
              title=%s
              description=%s
              experienceMonths=%d
              employmentType=%s
              postedDate=%s
              expiredDate=%s
              province=%s
              district=%s
              street=%s
              minSalary=%s
              maxSalary=%s
              currency=%s
              salaryPeriod=%s
              industry=%s
              skills=%s
              benefits=%s
            }
            """.formatted(
                url,
                companyName,
                companyLogoUrl,
                title,
                description,
                experienceMonths,
                employmentType,
                postedDate,
                expiredDate,
                province,
                district,
                street,
                minSalary,
                maxSalary,
                currency,
                salaryPeriod,
                industry,
                skills,
                benefits
        );
    }
}
