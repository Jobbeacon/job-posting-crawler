CREATE TABLE IF NOT EXISTS crawled_url (
     id UUID PRIMARY KEY,
     url TEXT NOT NULL,
     url_hash VARCHAR(64) NOT NULL,
     expired_at TIMESTAMPTZ,
     crawled_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_crawled_url_hash
    ON crawled_url(url_hash);