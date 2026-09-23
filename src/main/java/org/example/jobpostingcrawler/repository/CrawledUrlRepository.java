package org.example.jobpostingcrawler.repository;

import org.example.jobpostingcrawler.entity.CrawledUrl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CrawledUrlRepository extends JpaRepository<CrawledUrl, UUID> {

    Optional<CrawledUrl> findByUrlHash(String urlHash);
}
