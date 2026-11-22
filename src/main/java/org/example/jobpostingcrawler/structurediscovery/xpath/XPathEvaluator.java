package org.example.jobpostingcrawler.structurediscovery.xpath;

import org.example.jobpostingcrawler.structurediscovery.JobField;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;

/**
 * Evaluates an XPath the same way the crawler's HtmlJobPostingExtractor will (jsoup selectXpath, "/@attr"
 * suffix for attributes, matched texts joined with "|"), so a discovered XPath is validated against the
 * semantics it will actually be used with.
 */
public final class XPathEvaluator {

    public record Evaluation(Elements elements, String value) {
        public boolean isEmpty() {
            return elements.isEmpty() || value == null || value.isBlank();
        }

        public Element first() {
            return elements.isEmpty() ? null : elements.first();
        }

        public int count() {
            return elements.size();
        }
    }

    private static final Evaluation EMPTY = new Evaluation(new Elements(), null);

    private XPathEvaluator() {
    }

    public static Evaluation evaluate(Document doc, String xpath, JobField.Kind kind) {
        if (doc == null || xpath == null || xpath.isBlank()) {
            return EMPTY;
        }
        try {
            String elementXpath = xpath;
            String attribute = null;
            int attrIdx = xpath.lastIndexOf("/@");
            if (attrIdx > 0 && !xpath.substring(attrIdx).contains("]")) {
                elementXpath = xpath.substring(0, attrIdx);
                attribute = xpath.substring(attrIdx + 2);
            }
            Elements elements = doc.selectXpath(elementXpath);
            if (elements.isEmpty()) {
                return EMPTY;
            }
            String value;
            if (attribute != null) {
                value = elements.attr(attribute).trim();
            } else if (kind == JobField.Kind.LIST) {
                List<String> items = elements.first().children().stream()
                        .map(Element::text).map(String::trim).filter(s -> !s.isEmpty()).toList();
                value = items.isEmpty() ? elements.first().text().trim() : String.join(", ", items);
            } else {
                value = String.join("|", elements.eachText()).trim();
            }
            return new Evaluation(elements, value);
        } catch (Exception e) {
            return EMPTY;
        }
    }
}
