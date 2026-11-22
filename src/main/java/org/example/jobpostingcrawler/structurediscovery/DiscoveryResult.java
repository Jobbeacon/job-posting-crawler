package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.model.ExtractorConfig;

import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a discovery run for one domain.
 *
 * @param domain     e.g. topcv.vn
 * @param domainKey  primary key of domain_extractor_config (dots replaced by underscores)
 * @param sampleUrls pages used
 * @param fields     located fields (missing = not found / no candidate generalised)
 * @param warnings   human-readable issues (missing required fields, LLM failures...)
 */
public record DiscoveryResult(
        String domain,
        String domainKey,
        List<String> sampleUrls,
        Map<JobField, FieldResult> fields,
        List<String> warnings
) {

    /**
     * @param xpath        chosen XPath
     * @param strategy     how it was built
     * @param coverage     samples where it returned a non-empty value
     * @param agreement    samples where it selected the node the LLM picked independently
     * @param llmSamples   samples the LLM analysed
     * @param sampleCount  samples evaluated
     * @param sampleValues value per sample (truncated)
     * @param alternatives other valid candidates, best first
     */
    public record FieldResult(
            JobField field,
            String xpath,
            String strategy,
            int coverage,
            int agreement,
            int llmSamples,
            int sampleCount,
            List<String> sampleValues,
            List<String> alternatives
    ) {
    }

    /** All fields the crawler requires (title, description, location) were found. */
    public boolean isComplete() {
        return List.of(JobField.values()).stream()
                .filter(JobField::required)
                .allMatch(fields::containsKey);
    }

    /** Found XPaths keyed like domain_extractor_config.xpaths ("title", "companyName"...), field order. */
    public Map<String, String> toXpathMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (JobField f : JobField.values()) {
            FieldResult r = fields.get(f);
            if (r != null) {
                map.put(f.jsonKey(), r.xpath());
            }
        }
        return map;
    }

    public ExtractorConfig toExtractorConfig() {
        return ExtractorConfig.fromMap(toXpathMap());
    }

    /** Readable report: per field xpath, strategy, coverage and sample values. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Domain: ").append(domain).append(" (key ").append(domainKey).append(")\n");
        sb.append("Samples:\n");
        sampleUrls.forEach(u -> sb.append("  - ").append(u).append('\n'));
        for (JobField f : JobField.values()) {
            FieldResult r = fields.get(f);
            sb.append('\n').append(f.yamlKey()).append(f.required() ? " (required)" : "").append(": ");
            if (r == null) {
                sb.append("NOT FOUND\n");
                continue;
            }
            sb.append(r.xpath()).append('\n');
            sb.append("    strategy=").append(r.strategy())
                    .append(" coverage=").append(r.coverage()).append('/').append(r.sampleCount())
                    .append(" llmAgreement=").append(r.agreement()).append('/').append(r.llmSamples()).append('\n');
            for (int i = 0; i < r.sampleValues().size(); i++) {
                sb.append("    [").append(i + 1).append("] ").append(r.sampleValues().get(i)).append('\n');
            }
        }
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings:\n");
            warnings.forEach(w -> sb.append("  - ").append(w).append('\n'));
        }
        return sb.toString();
    }
}
