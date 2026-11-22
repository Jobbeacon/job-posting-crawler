-- XPaths previously configured in application.yaml (crawler.extractor.html.domains.itviec_com).
INSERT INTO domain_extractor_config (domain_key, domain, status, xpaths, attempts, finished_at, updated_at)
VALUES ('itviec_com', 'itviec.com', 'ACTIVE', '{
  "title": "//div[@class=''job-header-info'']/h1",
  "companyName": "//div[@class=''employer-name'']",
  "companyLogoUrl": "//img[contains(@class, ''employer-logo'')]/@src",
  "description": "//section[contains(@class, ''job-content'')]",
  "location": "//*[local-name()=''svg''][*[local-name()=''use'' and contains(@href,''#map-pin'')]]/following-sibling::span[contains(@class,''text-rich-grey'')][1]",
  "industry": "//div[normalize-space(.)=''Job Domain:'']/following-sibling::div[1]",
  "skills": "//div[normalize-space(.)=''Skills:'']/following-sibling::div[1]"
}'::jsonb, 0, now(), now())
ON CONFLICT (domain_key) DO NOTHING;
