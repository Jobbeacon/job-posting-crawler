package org.example.jobpostingcrawler.structurediscovery.xpath;

import org.example.jobpostingcrawler.structurediscovery.GeneratedClasses;

import org.example.jobpostingcrawler.structurediscovery.JobField;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds XPath candidates for an element chosen by the LLM, deterministically and from the REAL DOM.
 * Every returned candidate has been checked to select the target element on its own page.
 *
 * <p>Strategies (most robust first):
 * <ol>
 *   <li><b>id</b> - {@code //div[@id='job-title']} when the id looks hand-written (not generated).</li>
 *   <li><b>label</b> - anchored on a visible label, e.g.
 *       {@code //div[normalize-space(.)='Kinh nghiệm']/following-sibling::div[1]}; survives layout changes
 *       and works for grids of identical label/value boxes.</li>
 *   <li><b>class</b> - {@code //h1[contains(@class,'job-title')]}.</li>
 *   <li><b>ancestor</b> - a stable ancestor (id/class) followed by a class step or a child path.</li>
 *   <li><b>llm</b> - the XPath suggested by the LLM, if it is valid.</li>
 *   <li><b>absolute</b> - indexed path from /html (last resort, fragile).</li>
 * </ol>
 */
public class XPathCandidateGenerator {

    private static final Pattern GENERATED_ID = Pattern.compile(
            ".*\\d{3,}.*|^[:_].*|^(ember|react|mui|radix|headlessui|rc-|ng-|vue-|el-|uid|id-).*|.*[0-9a-f]{8,}.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_TOKEN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*$");
    private static final Pattern HASHED = Pattern.compile(
            "^(css|sc|jsx|emotion|styled)-[a-z0-9]{4,}$|.*\\d{3,}.*|.*__(?=[a-z]*[0-9])[a-z0-9]{5,}$|^[a-z]{1,3}[0-9][a-z0-9]{3,}$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STATE_CLASSES = Set.of(
            "active", "show", "open", "hidden", "selected", "lazy", "loaded", "lazyloaded", "collapsed", "collapse",
            "in", "fade", "disabled", "visible", "current", "expanded", "d-none", "d-block", "clearfix");
    /** Fields shown as "caption: value" on job boards. */
    private static final Set<JobField> LABELLED_FIELDS = Set.of(
            JobField.EXPERIENCE_MONTHS, JobField.EMPLOYMENT_TYPE, JobField.POSTED_DATE, JobField.EXPIRED_DATE,
            JobField.LOCATION, JobField.SALARY, JobField.INDUSTRY, JobField.SKILLS, JobField.BENEFITS);
    private static final Pattern UTILITY_CLASS = Pattern.compile(
            "^-?(m|p)[trblxyse]?-[0-9a-z.]+$|^(w|h|min-w|max-w|min-h|max-h|size)-.+|^(text|font|leading|tracking|bg"
                    + "|border|rounded|shadow|gap|space|items|justify|self|overflow|line-clamp|cursor|z|opacity"
                    + "|transition|duration|ease|whitespace|break|object|aspect|inset|translate|rotate|scale|col"
                    + "|grid-cols|grid-rows|flex|basis|grow|shrink|fs|fw|lh|align|g|gx|gy|d|float|ring|fill|stroke"
                    + "|divide)-.+$"
                    + "|^(flex|grid|block|inline|inline-block|inline-flex|hidden|relative|absolute|fixed|sticky|static"
                    + "|truncate|uppercase|lowercase|capitalize|italic|underline|container|row|col|clearfix|visible"
                    + "|invisible|shrink|grow|border|rounded|shadow|antialiased)$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SEMANTIC_TAGS = Set.of("h1", "h2", "h3", "time");
    private static final int MAX_LABEL_LENGTH = 50;
    /** Leading caption without digits ending with ':' ("Hạn nộp:", "Đăng:", "Mức lương:"). */
    private static final Pattern CAPTION_PREFIX = Pattern.compile("^([^\\d:]{2,30}:)");
    private static final int MAX_LABEL_UP = 3;
    private static final int MAX_ANCESTOR_UP = 5;
    private static final int MAX_REL_DEPTH = 4;

    /**
     * @param doc       page the target belongs to
     * @param target    element chosen by the LLM (for ATTRIBUTE fields, an img or a container of one)
     * @param field     field being located
     * @param labelHint visible label the LLM associated with the value (nullable)
     * @param llmXpath  XPath suggested by the LLM (nullable)
     * @return candidates that select the target on {@code doc}, deduplicated, in priority order
     */
    public List<XPathCandidate> generate(Document doc, Element target, JobField field, String labelHint,
                                         String llmXpath) {
        if (doc == null || target == null) {
            return List.of();
        }
        Element t = target;
        String attributeSuffix = "";
        if (field.kind() == JobField.Kind.ATTRIBUTE) {
            if (!t.normalName().equals("img")) {
                Element img = t.selectFirst("img");
                if (img == null) {
                    return List.of();
                }
                t = img;
            }
            attributeSuffix = "/@" + imageAttribute(t);
        }

        Map<String, XPathCandidate> raw = new LinkedHashMap<>();
        idCandidates(t, raw);
        labelCandidates(doc, t, field, labelHint, raw);
        prefixCandidates(t, field, labelHint, raw);
        classCandidates(t, raw);
        innerHeadingCandidates(t, field, raw);
        ancestorCandidates(t, raw);
        if (llmXpath != null && !llmXpath.isBlank()) {
            String x = llmXpath.trim().replaceAll("/text\\(\\)$", "").replaceAll("/@[\\w:-]+$", "");
            add(raw, x, "llm", 5);
        }
        add(raw, absolutePath(t), "absolute", 9);

        List<XPathCandidate> valid = new ArrayList<>();
        for (XPathCandidate c : raw.values()) {
            String full = c.xpath() + attributeSuffix;
            XPathEvaluator.Evaluation eval = XPathEvaluator.evaluate(doc, c.xpath(), JobField.Kind.TEXT);
            if (eval.elements().isEmpty()) {
                continue;
            }
            Element first = eval.first();
            boolean selectsTarget = first == t
                    || (field.kind() != JobField.Kind.ATTRIBUTE && first.text().equals(t.text()) && !t.text().isBlank());
            if (selectsTarget) {
                valid.add(new XPathCandidate(full, c.strategy(), c.priority()));
                // Matches more than the target (the same caption in "related jobs" cards further down): the
                // extractor would join every match with "|", so also offer "first match only".
                if (eval.count() > 1 && field.kind() != JobField.Kind.LIST && field != JobField.LOCATION) {
                    // ranked below the scoped variants (priority + 1) of the same strategy: "first match" depends
                    // on document order, a scoped XPath does not
                    valid.add(new XPathCandidate("(" + c.xpath() + ")[1]" + attributeSuffix, c.strategy(), c.priority() + 2));
                }
            }
        }
        return valid;
    }

    /** Picks the attribute holding the real image URL (lazy-loaded images keep it in data-src). */
    public static String imageAttribute(Element img) {
        String src = img.attr("src");
        if (!src.isBlank() && !src.startsWith("data:")) {
            return "src";
        }
        for (String lazy : List.of("data-src", "data-original", "data-lazy-src", "srcset")) {
            if (!img.attr(lazy).isBlank()) {
                return lazy;
            }
        }
        return "src";
    }

    // ------------------------------------------------------------------ strategies

    private void idCandidates(Element t, Map<String, XPathCandidate> out) {
        if (isStableId(t.id())) {
            add(out, "//" + t.normalName() + "[@id=" + literal(t.id()) + "]", "id", 0);
        }
    }

    private void classCandidates(Element t, Map<String, XPathCandidate> out) {
        String tag = t.normalName();
        // the first <h1> of a page is usually the title: no class needed at all. Only for meaningful tags: a bare
        // //span or //div "finds" something on every page, including pages where the field does not exist.
        if (SEMANTIC_TAGS.contains(tag)) {
            add(out, "//" + tag, "tag", 2);
        }

        List<String> classes = stableClasses(t);
        for (String c : classes) {
            if (isUtilityClass(c)) {
                // style-only class (Tailwind/Bootstrap): exact token only, ranked low since a restyle breaks it
                add(out, "//" + tag + "[" + exactClass(c) + "]", "class", 7);
            } else {
                add(out, "//" + tag + "[" + containsClass(c) + "]", "class", 2);
            }
        }
        List<String> semantic = classes.stream().filter(c -> !isUtilityClass(c)).toList();
        for (int i = 0; i < semantic.size(); i++) {
            for (int j = i + 1; j < semantic.size(); j++) {
                add(out, "//" + tag + "[" + containsClass(semantic.get(i)) + " and " + containsClass(semantic.get(j))
                        + "]", "class", 2);
            }
        }
        for (String c : semantic) {
            add(out, "//" + tag + "[" + exactClass(c) + "]", "class", 3);
        }
    }

    /**
     * Label and value in the same element ("Hạn nộp: 22/11/2026", "Đăng: 8 giờ trước"): match the element by its
     * stable caption prefix, e.g. {@code //span[starts-with(normalize-space(.),'Hạn nộp:')]}. The value keeps the
     * caption, which the field parsers ignore (they look for the number / date inside the text).
     */
    private void prefixCandidates(Element t, JobField field, String labelHint, Map<String, XPathCandidate> out) {
        if (!LABELLED_FIELDS.contains(field)) {
            return;
        }
        String text = normalize(t.text());
        String prefix = null;
        if (labelHint != null && !labelHint.isBlank()) {
            String hint = normalize(labelHint);
            if (text.toLowerCase(Locale.ROOT).startsWith(hint.toLowerCase(Locale.ROOT)) && text.length() > hint.length()) {
                prefix = text.substring(0, hint.length());
                if (text.length() > prefix.length() && text.charAt(prefix.length()) == ':') {
                    prefix = prefix + ":";
                }
            }
        }
        if (prefix == null) {
            Matcher m = CAPTION_PREFIX.matcher(text);
            if (m.find() && m.end() < text.length()) {
                prefix = m.group(1);
            }
        }
        if (prefix == null) {
            return;
        }
        String step = "//" + t.normalName() + "[starts-with(normalize-space(.)," + literal(prefix) + ")]";
        add(out, step, "label", 1);
        int scopes = 0;
        for (Element a = t.parent(); a != null && scopes < 3 && !a.normalName().equals("body"); a = a.parent()) {
            for (String anchor : anchorSteps(a)) {
                add(out, anchor + step, "label", 2);
                scopes++;
            }
        }
    }

    /**
     * Container fields (description, benefits, lists): anchor on the stable heading INSIDE the target, e.g.
     * {@code //h2[normalize-space(.)='Mô tả công việc']/..}. Works on utility-class sites where the
     * container has no meaningful class.
     */
    private void innerHeadingCandidates(Element t, JobField field, Map<String, XPathCandidate> out) {
        if (field.kind() == JobField.Kind.ATTRIBUTE || LABELLED_FIELDS.contains(field) && field.kind() != JobField.Kind.LIST
                && field != JobField.BENEFITS) {
            return;
        }
        String containerText = normalize(t.text());
        int checked = 0;
        for (Element h : t.select("*")) {
            if (h == t || checked++ > 30) {
                continue;
            }
            String text = normalize(h.text());
            if (text.length() < 3 || text.length() > MAX_LABEL_LENGTH || !containerText.startsWith(text)) {
                continue;
            }
            int depth = 0;
            for (Element p = h; p != t && p != null; p = p.parent()) {
                depth++;
            }
            if (depth > MAX_LABEL_UP + 1) {
                continue;
            }
            // the wrapper of a heading has the same text: go down to the heading element itself
            while (h.childrenSize() == 1 && normalize(h.child(0).text()).equals(text)) {
                h = h.child(0);
                depth++;
            }
            String headingXpath = labelXpath(h);
            if (headingXpath != null) {
                add(out, headingXpath + "/..".repeat(depth), "heading", 3);
            }
            return; // only the leading heading is meaningful
        }
    }

    private void ancestorCandidates(Element t, Map<String, XPathCandidate> out) {
        List<String> targetSteps = new ArrayList<>();
        for (String c : stableClasses(t).stream().filter(c -> !isUtilityClass(c)).toList()) {
            targetSteps.add("//" + t.normalName() + "[" + containsClass(c) + "]");
        }
        Element a = t.parent();
        for (int up = 1; a != null && up <= MAX_ANCESTOR_UP && !a.normalName().equals("body"); up++, a = a.parent()) {
            String rel = relativePath(a, t);
            // positional steps (div[4]/div[2]) break as soon as an optional block is missing: rank them low
            int relPriority = rel != null && rel.contains("[") ? 6 : 4;
            for (String anchor : anchorSteps(a)) {
                for (String step : targetSteps) {
                    add(out, anchor + step, "ancestor", 4);
                }
                if (rel != null) {
                    add(out, anchor + rel, "ancestor", relPriority);
                }
            }
        }
    }

    /**
     * Label strategy: find the label element (LLM hint, or the short text just before the value) and walk
     * from it to the target with parent / following-sibling / child steps.
     */
    private void labelCandidates(Document doc, Element t, JobField field, String labelHint,
                                 Map<String, XPathCandidate> out) {
        Map<Element, Integer> labels = new LinkedHashMap<>(); // label element -> priority
        if (labelHint != null && !labelHint.isBlank()) {
            Element hinted = findLabelElement(doc, t, labelHint);
            if (hinted != null) {
                labels.put(hinted, 1);
            }
        }
        // Guessed labels only make sense for caption/value fields, and rank below class-based XPaths.
        if (LABELLED_FIELDS.contains(field)) {
            Element implicit = implicitLabel(t);
            if (implicit != null) {
                labels.putIfAbsent(implicit, 3);
            }
        }
        for (Map.Entry<Element, Integer> entry : labels.entrySet()) {
            Element label = entry.getKey();
            int priority = entry.getValue();
            String labelXpath = labelXpath(label);
            if (labelXpath == null) {
                continue;
            }
            String path = pathFromLabel(label, t);
            if (path == null) {
                continue;
            }
            add(out, labelXpath + path, "label", priority);
            // Same label scoped by a stable ancestor, for labels that also appear elsewhere on the page
            // (e.g. "Kinh nghiệm" in the header box and in a search filter).
            int scopes = 0;
            Element a = label.parent();
            for (int up = 1; a != null && up <= MAX_ANCESTOR_UP + 1 && scopes < 3
                    && !a.normalName().equals("body"); up++, a = a.parent()) {
                for (String anchor : anchorSteps(a)) {
                    add(out, anchor + labelXpath + path, "label", Math.max(2, priority));
                    scopes++;
                }
            }
        }
    }

    /** "//div[@id='x']" / "//div[contains(@class,'c')]" steps identifying an ancestor. */
    private static List<String> anchorSteps(Element a) {
        List<String> anchors = new ArrayList<>();
        if (isStableId(a.id())) {
            anchors.add("//" + a.normalName() + "[@id=" + literal(a.id()) + "]");
        }
        // style-only classes are shared by half the page: useless as anchors
        for (String c : stableClasses(a).stream().filter(c -> !isUtilityClass(c)).limit(2).toList()) {
            anchors.add("//" + a.normalName() + "[" + containsClass(c) + "]");
        }
        return anchors;
    }

    /** Tailwind / Bootstrap style-only class (layout, spacing, typography, colors...). */
    static boolean isUtilityClass(String c) {
        return UTILITY_CLASS.matcher(c).matches();
    }

    /** Closest element (to the target) whose own/full text equals the label hint. */
    private Element findLabelElement(Document doc, Element t, String labelHint) {
        String wanted = normalizeLabel(labelHint);
        Element best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Element e : doc.body().getAllElements()) {
            if (e == t || e.text().length() > MAX_LABEL_LENGTH * 2) {
                continue;
            }
            String own = normalizeLabel(e.ownText());
            String full = normalizeLabel(e.text());
            if (!wanted.equals(own) && !wanted.equals(full)) {
                continue;
            }
            int distance = treeDistance(e, t);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = e;
            }
        }
        return bestDistance <= 2 * (MAX_LABEL_UP + MAX_REL_DEPTH) ? best : null;
    }

    /** Short text right before the value: previous sibling of the target or of its parent. */
    private Element implicitLabel(Element t) {
        Element e = t;
        for (int up = 0; up < 2 && e != null; up++, e = e.parent()) {
            Element prev = e.previousElementSibling();
            if (prev != null) {
                String text = prev.text().trim();
                if (!text.isEmpty() && text.length() <= MAX_LABEL_LENGTH && !text.equals(t.text().trim())) {
                    // descend to the element that really owns the label text
                    while (prev.children().size() == 1 && prev.ownText().isBlank()) {
                        prev = prev.child(0);
                    }
                    return prev;
                }
                return null;
            }
        }
        return null;
    }

    private String labelXpath(Element label) {
        // A label with nested text (icon ligatures like <i>event</i>Hết hạn nộp) is matched on its own text
        // node, so the icon name does not end up in the XPath.
        boolean nestedText = label.children().stream().anyMatch(c -> !c.text().isBlank());
        String ownText = label.textNodes().stream().map(tn -> normalize(tn.text()))
                .filter(s -> !s.isEmpty()).findFirst().orElse("");
        String full = normalize(label.text());
        if (!full.isEmpty() && full.length() <= MAX_LABEL_LENGTH && !(nestedText && !ownText.isEmpty())) {
            return "//" + label.normalName() + "[normalize-space(.)=" + literal(full) + "]";
        }
        for (TextNode tn : label.textNodes()) {
            String own = normalize(tn.text());
            if (!own.isEmpty() && own.length() <= MAX_LABEL_LENGTH) {
                return "//" + label.normalName() + "[text()[normalize-space(.)=" + literal(own) + "]]";
            }
        }
        return null;
    }

    private String pathFromLabel(Element label, Element t) {
        Element a = label;
        for (int up = 0; up <= MAX_LABEL_UP && a != null; up++, a = a.parent()) {
            String ups = "/..".repeat(up);
            if (a == t) {
                return up == 0 ? null : ups;
            }
            if (a.parent() == null) {
                return null;
            }
            // following sibling that is (or contains) the target
            Map<String, Integer> sameTagCount = new LinkedHashMap<>();
            for (Element s = a.nextElementSibling(); s != null; s = s.nextElementSibling()) {
                int k = sameTagCount.merge(s.normalName(), 1, Integer::sum);
                if (s == t || isAncestor(s, t)) {
                    String rel = s == t ? "" : relativePath(s, t);
                    if (rel == null) {
                        return null;
                    }
                    return ups + "/following-sibling::" + s.normalName() + "[" + k + "]" + rel;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Child steps from ancestor (exclusive) to target (inclusive), e.g. "/div[2]/span"; null if too deep. */
    static String relativePath(Element ancestor, Element target) {
        List<String> steps = new ArrayList<>();
        for (Element e = target; e != ancestor; e = e.parent()) {
            if (e == null || steps.size() >= MAX_REL_DEPTH) {
                return null;
            }
            steps.add(0, step(e));
        }
        return steps.isEmpty() ? null : "/" + String.join("/", steps);
    }

    static String absolutePath(Element t) {
        List<String> steps = new ArrayList<>();
        for (Element e = t; e != null && !(e instanceof Document); e = e.parent()) {
            steps.add(0, step(e));
        }
        return "/" + String.join("/", steps);
    }

    private static String step(Element e) {
        String tag = e.normalName();
        Element parent = e.parent();
        if (parent == null) {
            return tag;
        }
        int index = 0;
        int total = 0;
        for (Element sibling : parent.children()) {
            if (sibling.normalName().equals(tag)) {
                total++;
                if (sibling == e) {
                    index = total;
                }
            }
        }
        return total > 1 ? tag + "[" + index + "]" : tag;
    }

    static boolean isStableId(String id) {
        return id != null && !id.isBlank() && id.length() <= 50 && VALID_TOKEN.matcher(id).matches()
                && !GENERATED_ID.matcher(id).matches();
    }

    public static List<String> stableClasses(Element e) {
        java.util.Set<String> all = e.classNames();
        return all.stream()
                .filter(c -> !GeneratedClasses.isGenerated(c, all))
                .filter(c -> c.length() >= 2 && c.length() <= 50)
                .filter(c -> VALID_TOKEN.matcher(c).matches())
                .filter(c -> !HASHED.matcher(c).matches())
                .filter(c -> !STATE_CLASSES.contains(c.toLowerCase(Locale.ROOT)))
                .limit(5)
                .toList();
    }

    private static String containsClass(String c) {
        return "contains(@class," + literal(c) + ")";
    }

    private static String exactClass(String c) {
        return "contains(concat(' ',normalize-space(@class),' ')," + literal(" " + c + " ") + ")";
    }

    /** XPath 1.0 string literal (no escaping exists, so switch quotes or use concat()). */
    public static String literal(String s) {
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(",\"'\",");
            }
            sb.append('\'').append(parts[i]).append('\'');
        }
        return sb.append(')').toString();
    }

    private static boolean isAncestor(Element ancestor, Element e) {
        for (Element p = e.parent(); p != null; p = p.parent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static int treeDistance(Element a, Element b) {
        List<Element> pathA = new ArrayList<>();
        for (Element e = a; e != null; e = e.parent()) {
            pathA.add(e);
        }
        int up = 0;
        for (Element e = b; e != null; e = e.parent(), up++) {
            int idx = pathA.indexOf(e);
            if (idx >= 0) {
                return idx + up;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normalizeLabel(String s) {
        return normalize(s).replaceAll("[:：]\\s*$", "").trim().toLowerCase(Locale.ROOT);
    }

    private static void add(Map<String, XPathCandidate> out, String xpath, String strategy, int priority) {
        if (xpath != null && !xpath.isBlank()) {
            out.putIfAbsent(xpath, new XPathCandidate(xpath, strategy, priority));
        }
    }
}
