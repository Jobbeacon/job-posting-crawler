package org.example.jobpostingcrawler.structurediscovery.llm;

import org.example.jobpostingcrawler.structurediscovery.JobField;

import java.util.Map;

/**
 * What the LLM picked on one simplified page.
 *
 * @param jobPosting whether the LLM thinks the page is a single job posting
 * @param picks      field -> pick (fields the LLM could not find are absent)
 * @param model      model that answered
 * @param promptTokens / outputTokens usage, nullable
 */
public record LlmSelection(
        boolean jobPosting,
        Map<JobField, FieldPick> picks,
        String model,
        Integer promptTokens,
        Integer outputTokens
) {
    /**
     * @param node  x-id of the chosen element in the simplified html
     * @param label visible label next to the value on the page (e.g. "Kinh nghiệm"), nullable
     * @param xpath optional XPath suggested by the LLM, nullable
     */
    public record FieldPick(Integer node, String label, String xpath) {
    }
}
