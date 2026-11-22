package org.example.jobpostingcrawler.structurediscovery;

import org.example.jobpostingcrawler.structurediscovery.llm.GeminiStructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.page.PlaywrightPageLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real run: Playwright + Gemini on live job boards. Skipped unless GEMINI_API_KEY is set.
 *
 * <pre>
 * $env:GEMINI_API_KEY="..."; ./mvnw test -Dtest=StructureDiscoveryLiveTest
 * </pre>
 * Optional: GEMINI_MODEL (default gemini-3.1-flash-lite).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class StructureDiscoveryLiveTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://www.topcv.vn/tim-viec-lam-moi-nhat, https://www.topcv.vn/viec-lam/",
            "https://www.vietnamworks.com/tim-viec-lam, https://www.vietnamworks.com/",
            // homepage: links more company pages than jobs (application-local.yaml seed)
            "https://www.vietnamworks.com, https://www.vietnamworks.com/",
            "https://itviec.com/it-jobs, https://itviec.com/it-jobs/",
    })
    void discoversStructureOfLiveSite(String seed, String prefix) {
        StructureDiscoveryProperties p = StructureDiscoveryProperties.of(System.getenv("GEMINI_API_KEY"),
                System.getenv("GEMINI_MODEL"), 3, 2, true);
        try (GeminiStructureLlmClient llm = StructureDiscoveryConfiguration.createLlmClient(p);
             PlaywrightPageLoader loader = new PlaywrightPageLoader(p.headless(), p.pageSettleTime())) {
            DiscoveryResult result = StructureDiscoveryConfiguration.createService(llm, p)
                    .discover(loader, List.of(seed), prefix, null, p.sampleCount());

            System.out.println(result.report());
            System.out.println(result.toXpathMap());

            assertFalse(result.fields().isEmpty(), result.report());
            assertTrue(result.fields().containsKey(JobField.TITLE), result.report());
        }
    }
}
