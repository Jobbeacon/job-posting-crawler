package org.example.jobpostingcrawler.structurediscovery.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import org.example.jobpostingcrawler.structurediscovery.JobField;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prompt, response schema and response parsing for the structure discovery LLM call. */
public final class StructurePrompt {

    public static final String SYSTEM_INSTRUCTION = """
            You are an expert in HTML structure analysis for web scraping of job boards.
            You receive a SIMPLIFIED outline of ONE job posting page. Every element has an attribute x="N"
            (a unique node id). Long texts are truncated ("…(+N chars)") and runs of similar siblings are
            collapsed ("<!-- +N similar ... -->"); scripts, menus, modals and page chrome were removed.

            Task: for each requested field, return the node id (x) of the element that holds the value
            for THE MAIN job posting of the page.

            Rules:
            - Only use x values that appear in the outline. Never invent ids.
            - Prefer the most specific element that contains the value and as little else as possible
              (ideally NOT the label). E.g. for <div x="5"><div x="6">Kinh nghiệm</div><div x="7">2 năm</div></div>
              answer node 7 with label "Kinh nghiệm".
            - "label": the visible caption shown next to the value, copied EXACTLY from the outline
              (e.g. "Kinh nghiệm", "Hạn nộp hồ sơ", "Mức lương", "Skills:"). null when the value has no caption
              (e.g. the title heading, the company name link).
            - For list fields (skills, industry) return the CONTAINER whose direct children are the items.
            - For description return the smallest container covering all description sections.
            - Ignore elements belonging to related/suggested jobs, ads, navigation, footers or other companies.
            - "xpath": optionally an XPath 1.0 expression (no /text()) that would select the same element on
              other job pages of the same site, based on stable classes/ids/labels. null if unsure.
            - If a field does not exist on the page, set node to null.
            - isJobPosting=false if the page is not a single job posting (listing, search page, error page...).
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructurePrompt() {
    }

    public static String userPrompt(SimplifiedPage page, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("Page URL: ").append(url).append('\n');
        sb.append("Page title: ").append(page.titleHint()).append("\n\n");
        sb.append("Fields to locate:\n");
        for (JobField f : JobField.values()) {
            sb.append("- ").append(f.jsonKey()).append(": ").append(f.description()).append('\n');
        }
        sb.append("\nSimplified page outline:\n<page>\n").append(page.html()).append("\n</page>\n");
        return sb.toString();
    }

    public static Schema responseSchema() {
        Schema pick = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "node", Schema.builder().type(Type.Known.INTEGER).nullable(true).build(),
                        "label", Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                        "xpath", Schema.builder().type(Type.Known.STRING).nullable(true).build()))
                .required(List.of("node", "label", "xpath"))
                .build();
        Map<String, Schema> fieldProps = new LinkedHashMap<>();
        List<String> fieldNames = new ArrayList<>();
        for (JobField f : JobField.values()) {
            fieldProps.put(f.jsonKey(), pick);
            fieldNames.add(f.jsonKey());
        }
        Schema fields = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(fieldProps)
                .required(fieldNames)
                .propertyOrdering(fieldNames)
                .build();
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "isJobPosting", Schema.builder().type(Type.Known.BOOLEAN).build(),
                        "fields", fields))
                .required(List.of("isJobPosting", "fields"))
                .propertyOrdering(List.of("isJobPosting", "fields"))
                .build();
    }

    /** Parses the model JSON; unknown node ids are dropped by the caller. */
    public static LlmSelection parse(String json, String model, Integer promptTokens, Integer outputTokens) {
        if (json == null || json.isBlank()) {
            throw new InvalidLlmResponseException("Empty LLM response");
        }
        try {
            JsonNode root = MAPPER.readTree(stripCodeFence(json));
            Map<JobField, LlmSelection.FieldPick> picks = new EnumMap<>(JobField.class);
            JsonNode fields = root.path("fields");
            for (JobField f : JobField.values()) {
                JsonNode p = fields.path(f.jsonKey());
                if (p.isMissingNode() || p.isNull()) {
                    continue;
                }
                Integer node = p.path("node").canConvertToInt() && !p.path("node").isNull()
                        ? p.path("node").asInt() : null;
                if (node == null) {
                    continue;
                }
                picks.put(f, new LlmSelection.FieldPick(node, textOrNull(p.path("label")), textOrNull(p.path("xpath"))));
            }
            return new LlmSelection(root.path("isJobPosting").asBoolean(true), picks, model, promptTokens, outputTokens);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidLlmResponseException("Invalid LLM JSON response", e);
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String stripCodeFence(String json) {
        String trimmed = json.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }
}
