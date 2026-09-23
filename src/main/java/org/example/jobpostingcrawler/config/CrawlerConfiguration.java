package org.example.jobpostingcrawler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CrawlerProperties.class, PlaywrightProperties.class})
public class CrawlerConfiguration {
}
