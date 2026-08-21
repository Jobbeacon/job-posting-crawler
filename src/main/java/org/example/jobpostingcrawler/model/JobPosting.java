package org.example.jobpostingcrawler.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class JobPosting {
    private String title;
    private String companyName;
    private String companyLogoUrl;
    private String description;
    private LocalDateTime postedDate;
    private LocalDateTime expiredDate;
    private String employmentType;
    private String province;
    private String district;
    private String street;
    private Long minSalary;
    private Long maxSalary;
    private String currency;
    private String salaryPeriod;
    private String industry;
    private List<String> skills;
    private String benefit;
}
