package org.example.jobpostingcrawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class JobPostingCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobPostingCrawlerApplication.class, args);
    }

}
