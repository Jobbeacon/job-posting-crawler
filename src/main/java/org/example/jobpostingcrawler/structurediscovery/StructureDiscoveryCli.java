package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.structurediscovery.llm.GeminiStructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.page.PlaywrightPageLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs a discovery without starting the crawler (no Redis/RabbitMQ/DB needed) and prints the SQL that stores
 * the XPaths in domain_extractor_config as ACTIVE.
 *
 * <pre>
 * GEMINI_API_KEY=... java ... StructureDiscoveryCli \
 *     --seed https://www.topcv.vn/tim-viec-lam-moi-nhat --prefix https://www.topcv.vn/viec-lam/ \
 *     [--samples url1,url2] [--count 4] [--llm-count 2] [--model gemini-3.1-flash-lite] [--out result.sql] [--headed]
 * </pre>
 */
public final class StructureDiscoveryCli {

    private StructureDiscoveryCli() {
    }

    public static void main(String[] args) throws Exception {
        List<String> seeds = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        String prefix = null;
        String model = null;
        String out = null;
        int count = 0;
        int llmCount = 0;
        boolean headless = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed" -> seeds.add(args[++i]);
                case "--prefix" -> prefix = args[++i];
                case "--samples" -> samples.addAll(Arrays.asList(args[++i].split(",")));
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--llm-count" -> llmCount = Integer.parseInt(args[++i]);
                case "--model" -> model = args[++i];
                case "--out" -> out = args[++i];
                case "--headed" -> headless = false;
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (seeds.isEmpty() && samples.isEmpty()) {
            System.err.println("Usage: --seed <listing url> --prefix <job url prefix> | --samples <url,url>");
            System.exit(2);
        }

        StructureDiscoveryProperties p = StructureDiscoveryProperties.of(System.getenv("GEMINI_API_KEY"), model,
                count, llmCount, headless);

        try (GeminiStructureLlmClient llm = StructureDiscoveryConfiguration.createLlmClient(p);
             PlaywrightPageLoader loader = new PlaywrightPageLoader(p.headless(), p.pageSettleTime())) {
            DiscoveryResult result = StructureDiscoveryConfiguration.createService(llm, p)
                    .discover(loader, seeds, prefix, samples, p.sampleCount());
            System.out.println(result.report());
            String sql = upsertSql(result);
            System.out.println("----- SQL -----");
            System.out.println(sql);
            if (out != null) {
                Files.writeString(Path.of(out), sql, StandardCharsets.UTF_8);
                System.out.println("Written to " + out);
            }
        }
    }

    /** INSERT ... ON CONFLICT UPDATE of the domain row with the found XPaths, status ACTIVE. */
    static String upsertSql(DiscoveryResult result) throws Exception {
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.toXpathMap());
        String quoted = "'" + json.replace("'", "''") + "'";
        return "INSERT INTO domain_extractor_config (domain_key, domain, status, xpaths, attempts, finished_at, updated_at)\n"
                + "VALUES ('" + result.domainKey() + "', '" + result.domain() + "', 'ACTIVE', " + quoted
                + "::jsonb, 0, now(), now())\n"
                + "ON CONFLICT (domain_key) DO UPDATE SET status = 'ACTIVE', xpaths = EXCLUDED.xpaths, error = NULL,\n"
                + "    next_retry_at = NULL, finished_at = now(), updated_at = now();\n";
    }
}
