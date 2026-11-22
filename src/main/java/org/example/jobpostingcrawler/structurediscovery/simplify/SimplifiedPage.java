package org.example.jobpostingcrawler.structurediscovery.simplify;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Map;

/**
 * Compact, LLM-friendly view of a page. Every element rendered in {@link #html()} carries an {@code x="N"}
 * attribute; {@link #node(int)} maps that number back to the element of the ORIGINAL document, so XPath
 * generation always works on the real DOM, never on the simplified text.
 *
 * @param document      original parsed page
 * @param html          simplified markup sent to the LLM
 * @param nodes         x-id -> original element
 * @param titleHint     page title taken from og:title / &lt;title&gt; / h1
 * @param anchor        element believed to be the job title (may be null)
 * @param focus         root element that was rendered (body, or an ancestor of the anchor when the page was too big)
 * @param originalChars size of the original html
 */
public record SimplifiedPage(
        Document document,
        String html,
        Map<Integer, Element> nodes,
        String titleHint,
        Element anchor,
        Element focus,
        int originalChars
) {
    public Element node(Integer id) {
        return id == null ? null : nodes.get(id);
    }

    public String stats() {
        return "original=%d chars, simplified=%d chars, nodes=%d, focus=<%s%s>".formatted(
                originalChars, html.length(), nodes.size(),
                focus == null ? "?" : focus.normalName(),
                focus == null || focus.className().isBlank() ? "" : " class=\"" + focus.className() + "\"");
    }
}
