-- Layout change detection (STALE): coverage of each field when the XPaths were found, fields whose XPath
-- stopped working on recent pages, and since when the domain is STALE.
ALTER TABLE domain_extractor_config ADD COLUMN IF NOT EXISTS field_coverage JSONB;  -- {"title": 1.0, "skills": 0.67}
ALTER TABLE domain_extractor_config ADD COLUMN IF NOT EXISTS stale_fields JSONB;    -- ["location", "salary"]
ALTER TABLE domain_extractor_config ADD COLUMN IF NOT EXISTS stale_since TIMESTAMPTZ;
