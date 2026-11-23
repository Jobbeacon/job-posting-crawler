package org.example.jobpostingcrawler.repository;

import org.example.jobpostingcrawler.entity.CrawledUrl;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrawledUrlRepository extends JpaRepository<CrawledUrl, UUID> {

    Optional<CrawledUrl> findByUrlHash(String urlHash);

    /** Rows in id order after {@code after}, to walk the whole table in batches. */
    List<CrawledUrl> findByIdGreaterThanOrderById(UUID after, Limit limit);
}
