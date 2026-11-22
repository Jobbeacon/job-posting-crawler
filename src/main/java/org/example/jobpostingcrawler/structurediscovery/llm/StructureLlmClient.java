package org.example.jobpostingcrawler.structurediscovery.llm;

import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;

/** Asks an LLM which simplified-page nodes hold each job field. */
@FunctionalInterface
public interface StructureLlmClient {

    LlmSelection select(SimplifiedPage page, String url);
}
