package org.example.jobpostingcrawler.config;

import org.example.jobpostingcrawler.extractor.health.ExtractionHealthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CrawlerProperties.class, PlaywrightProperties.class, ExtractionHealthProperties.class})
public class CrawlerConfiguration {
}
