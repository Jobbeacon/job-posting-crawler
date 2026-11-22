package org.example.jobpostingcrawler.repository;

import org.example.jobpostingcrawler.entity.DomainExtractorConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainExtractorConfigRepository extends JpaRepository<DomainExtractorConfig, String> {
}
