-- XPath configuration of the HTML extractor, one row per domain (domain_key = domain with '.' replaced by '_').
-- Rows are written by the structure discovery module, or by hand (UPDATE xpaths + status = 'ACTIVE').
CREATE TABLE IF NOT EXISTS domain_extractor_config (
    domain_key    VARCHAR(255) PRIMARY KEY,
    domain        VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL,            -- RUNNING | ACTIVE | FAILED
    xpaths        JSONB,                            -- {"title": "//h1", "companyName": "...", ...}
    report        TEXT,
    warnings      JSONB,
    sample_urls   JSONB,
    error         TEXT,
    attempts      INT          NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
