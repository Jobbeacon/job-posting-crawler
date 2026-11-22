package org.example.jobpostingcrawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;

/**
 * Row of domain_extractor_config: the XPath configuration of one domain and the state of its structure
 * discovery. JSON columns are mapped as raw JSON strings (cast to jsonb on write).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "domain_extractor_config")
public class DomainExtractorConfig {

    public enum Status {
        /** Discovery is running (also used as a lock against a second run). */
        RUNNING,
        /** XPaths are used by the HTML extractor. */
        ACTIVE,
        /** Discovery failed; retried at next_retry_at. The crawler uses JSON-LD only meanwhile. */
        FAILED,
        /**
         * The site layout changed: XPaths of stale_fields stopped working on recent pages. The other XPaths are
         * still used; discovery runs again at next_retry_at (at most once a day).
         */
        STALE
    }

    @Id
    @Column(name = "domain_key", nullable = false, updatable = false)
    private String domainKey;

    @Column(name = "domain", nullable = false)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "xpaths", columnDefinition = "jsonb")
    private String xpaths;

    @Column(name = "report")
    private String report;

    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "warnings", columnDefinition = "jsonb")
    private String warnings;

    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "sample_urls", columnDefinition = "jsonb")
    private String sampleUrls;

    @Column(name = "error")
    private String error;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Share of the discovery samples where each field was found: {"title": 1.0, "skills": 0.67}. */
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "field_coverage", columnDefinition = "jsonb")
    private String fieldCoverage;

    /** Fields whose XPath no longer works: ["location"]. Not used by the HTML extractor. */
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "stale_fields", columnDefinition = "jsonb")
    private String staleFields;

    @Column(name = "stale_since")
    private Instant staleSince;
}
