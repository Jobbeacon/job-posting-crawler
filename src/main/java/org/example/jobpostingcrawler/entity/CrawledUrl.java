package org.example.jobpostingcrawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "crawled_url")
public class CrawledUrl {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "url")
    private String url;

    @Column(name = "url_hash")
    private String urlHash;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "crawled_at")
    private Instant crawledAt;
}
