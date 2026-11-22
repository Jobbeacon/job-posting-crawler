package org.example.jobpostingcrawler.extractor.health;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * crawler.extractor.health.*: detection of XPaths broken by a site layout change.
 *
 * @param enabled          watch the XPaths of ACTIVE domains
 * @param window           pages kept per domain (sliding window)
 * @param minPages         pages needed before a field can be declared broken
 * @param healthyBaseline  only fields found on at least this share of the discovery samples are watched
 * @param brokenBelow      a watched field is broken when its share of valid values falls below this
 * @param evaluateEvery    pages between two evaluations of a domain
 * @param rediscoveryDelay minimum time between two discoveries of the same domain (at most once a day)
 */
@ConfigurationProperties(prefix = "crawler.extractor.health")
public record ExtractionHealthProperties(
        Boolean enabled,
        int window,
        int minPages,
        double healthyBaseline,
        double brokenBelow,
        int evaluateEvery,
        Duration rediscoveryDelay
) {
    public ExtractionHealthProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        window = window > 0 ? window : 100;
        minPages = minPages > 0 ? minPages : 30;
        healthyBaseline = healthyBaseline > 0 ? healthyBaseline : 0.8;
        brokenBelow = brokenBelow > 0 ? brokenBelow : 0.2;
        evaluateEvery = evaluateEvery > 0 ? evaluateEvery : 10;
        rediscoveryDelay = rediscoveryDelay == null ? Duration.ofHours(24) : rediscoveryDelay;
    }

    public static ExtractionHealthProperties defaults() {
        return new ExtractionHealthProperties(null, 0, 0, 0, 0, 0, null);
    }
}
