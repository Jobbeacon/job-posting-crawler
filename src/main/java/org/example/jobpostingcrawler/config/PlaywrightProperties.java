package org.example.jobpostingcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "playwright")
public record PlaywrightProperties(
    boolean headless,
    List<String> proxies
) {
}
