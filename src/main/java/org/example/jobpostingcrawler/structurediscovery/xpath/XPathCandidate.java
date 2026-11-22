package org.example.jobpostingcrawler.structurediscovery.xpath;

/**
 * @param xpath    XPath in the format expected by HtmlJobPostingExtractor
 * @param strategy how it was built (id, label, class, ancestor, llm, absolute)
 * @param priority lower = more robust, used as tie-breaker
 */
public record XPathCandidate(String xpath, String strategy, int priority) {
}
