package org.example.jobpostingcrawler.structurediscovery.simplify;

import org.example.jobpostingcrawler.structurediscovery.GeneratedClasses;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a full job page (often 0.3 - 2 MB of HTML) into a compact outline that fits an LLM prompt
 * (default 40k chars) while keeping what is needed to recognise fields: tag names, ids, meaningful classes,
 * labels and (truncated) texts.
 *
 * <p>Reduction steps:
 * <ol>
 *   <li>Drop non-content nodes: script/style/svg internals/iframes/forms controls, hidden elements, modals,
 *       popups and page chrome (header/footer/nav) unless they contain the job title.</li>
 *   <li>Drop empty elements (no text and no image).</li>
 *   <li>Skip "pass-through" wrappers: an element without own text and with a single child is not printed,
 *       its child is printed instead.</li>
 *   <li>Unwrap inline formatting tags (b, strong, em...) into plain text.</li>
 *   <li>Collapse runs of similar siblings (related jobs, menu items...) to the first few.</li>
 *   <li>Truncate long texts.</li>
 *   <li>If still over budget: tighten limits, then render only the largest ancestor of the job title that
 *       fits the budget, and finally hard-cut.</li>
 * </ol>
 */
public class HtmlSimplifier {

    public record Options(int maxChars, int maxTextLength, int maxRepeatedSiblings) {
        public static Options defaults() {
            return new Options(40_000, 120, 3);
        }

        Options tighter() {
            return new Options(maxChars, Math.max(30, maxTextLength / 2), Math.max(1, maxRepeatedSiblings - 1));
        }
    }

    private static final Set<String> SKIP_TAGS = Set.of(
            "script", "style", "noscript", "template", "iframe", "link", "meta", "head", "object", "embed",
            "canvas", "video", "audio", "source", "track", "map", "input", "select", "textarea", "option",
            "button", "dialog", "base", "param", "picture");
    private static final Set<String> CHROME_TAGS = Set.of("header", "footer", "nav");
    private static final Set<String> CHROME_ROLES = Set.of("banner", "navigation", "contentinfo", "dialog",
            "alertdialog", "menu", "menubar", "search");
    private static final Set<String> INLINE_UNWRAP = Set.of(
            "b", "strong", "i", "em", "u", "font", "small", "mark", "sup", "sub", "abbr", "s", "del", "ins");
    private static final Set<String> NO_PASS_THROUGH = Set.of("img", "ul", "ol", "table", "tbody", "tr", "dl", "h1",
            "h2", "h3", "h4", "h5", "h6", "section", "article", "main");
    private static final Pattern CHROME_CLASS = Pattern.compile(
            "(^|[-_])(modal|popup|pop-up|tooltip|dropdown-menu|offcanvas|cookie|overlay|toast|lightbox"
                    + "|navigation|navbar|nav|menu|sub-menu|submenu|breadcrumbs?|mega-menu)([-_]|$)");
    /** Runs of similar siblings longer than this are always collapsed (menus, long lists). */
    private static final int LONG_RUN = 10;
    /** Siblings bigger than this (in elements) are collapsed after the first few (related-job cards...). */
    private static final int BIG_ITEM_ELEMENTS = 8;
    private static final Pattern HIDDEN_STYLE = Pattern.compile("display\\s*:\\s*none|visibility\\s*:\\s*hidden");
    private static final Pattern RESPONSIVE_DISPLAY = Pattern.compile(
            "^d-(sm|md|lg|xl|xxl)-(?!none).+|^(sm|md|lg|xl|2xl):(block|flex|grid|inline|inline-block|inline-flex|table|contents)$");
    /** Tailwind sizing classes used as svg "names" carry no meaning (w-4, h-5, size-6). */
    private static final Pattern UTILITY_ICON = Pattern.compile("^(w|h|size|text|fill|stroke|icon)-[\\w.]+$");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final Options options;

    public HtmlSimplifier() {
        this(Options.defaults());
    }

    public HtmlSimplifier(Options options) {
        this.options = options;
    }

    public SimplifiedPage simplify(Document doc) {
        String titleHint = titleHint(doc);
        Element anchor = findAnchor(doc, titleHint);
        Set<Element> anchorPath = anchorPath(anchor);
        Map<Element, Integer> textLength = computeTextLength(doc);
        int originalChars = doc.outerHtml().length();
        Element body = doc.body();

        // Ancestors of the job title, outermost first: the focus candidates when the whole body is too big.
        List<Element> ancestors = new ArrayList<>();
        for (Element e = anchor; e != null && e != body; e = e.parent()) {
            ancestors.add(e);
        }
        Collections.reverse(ancestors);

        // For each detail level: whole body, else the largest region around the job title that fits.
        Options current = options;
        for (int level = 0; level < 3; level++) {
            Renderer r = new Renderer(current, anchorPath, anchor, textLength);
            r.render(body);
            if (r.length() <= current.maxChars()) {
                return r.toPage(doc, titleHint, anchor, body, originalChars);
            }
            for (Element candidate : ancestors) {
                // a region that is just the title (or barely more) is useless for the other fields
                if (textLength.getOrDefault(candidate, 0) < 500 && candidate != ancestors.get(0)) {
                    break;
                }
                r = new Renderer(current, anchorPath, anchor, textLength);
                r.render(candidate);
                if (r.length() <= current.maxChars()) {
                    return r.toPage(doc, titleHint, anchor, candidate, originalChars);
                }
            }
            current = current.tighter();
        }

        // Last resort: hard cut.
        Renderer r = new Renderer(current, anchorPath, anchor, textLength);
        r.render(body);
        r.truncate(current.maxChars());
        return r.toPage(doc, titleHint, anchor, body, originalChars);
    }

    // ------------------------------------------------------------------ anchor (job title) detection

    static String titleHint(Document doc) {
        for (String query : List.of("meta[property=og:title]", "meta[name=twitter:title]")) {
            Element meta = doc.selectFirst(query);
            if (meta != null && !meta.attr("content").isBlank()) {
                return normalize(meta.attr("content"));
            }
        }
        String title = normalize(doc.title());
        if (!title.isBlank()) {
            return title;
        }
        Element h1 = doc.selectFirst("h1");
        return h1 == null ? "" : normalize(h1.text());
    }

    static Element findAnchor(Document doc, String titleHint) {
        String hint = titleHint == null ? "" : titleHint.toLowerCase(Locale.ROOT);
        Element best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Element h : doc.select("h1, h2")) {
            String text = normalize(h.text()).toLowerCase(Locale.ROOT);
            if (text.isEmpty() || isHidden(h)) {
                continue;
            }
            int score = h.normalName().equals("h1") ? 10 : 0;
            if (!hint.isEmpty() && (hint.contains(text) || text.contains(hint))) {
                score += 20;
            } else if (!hint.isEmpty() && hint.startsWith(text.substring(0, Math.min(text.length(), 20)))) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        if (bestScore >= 10 || hint.isEmpty()) {
            return bestScore >= 10 ? best : null;
        }
        return findAnchorByTitleText(doc, hint);
    }

    /**
     * Sites that do not use a heading for the job title (e.g. a styled link): the visible element whose
     * text is the longest piece of the page title ("Recruiting {title} at {company} | Site").
     */
    private static Element findAnchorByTitleText(Document doc, String hint) {
        Element best = null;
        int bestIndex = Integer.MAX_VALUE;
        int bestLength = 0;
        int minLength = Math.min(12, Math.max(8, hint.length() / 4));
        for (Element e : doc.body().getAllElements()) {
            if (SKIP_TAGS.contains(e.normalName()) || e.childrenSize() > 3) {
                continue;
            }
            String text = normalize(e.text()).toLowerCase(Locale.ROOT);
            if (text.length() < minLength || text.length() > 200) {
                continue;
            }
            int index = hint.indexOf(text);
            // the title comes first in "{title} at {company} | {site}" / "{title} - {company}" patterns
            if (index < 0 || index > bestIndex || (index == bestIndex && text.length() <= bestLength)) {
                continue;
            }
            if (isHidden(e) || e.parents().stream().anyMatch(HtmlSimplifier::isHidden)) {
                continue;
            }
            best = e;
            bestIndex = index;
            bestLength = text.length();
        }
        return best;
    }

    private static Set<Element> anchorPath(Element anchor) {
        Set<Element> path = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element e = anchor; e != null; e = e.parent()) {
            path.add(e);
        }
        return path;
    }

    /** Text length of every element (post-order, linear time). Images count as content. */
    private static Map<Element, Integer> computeTextLength(Document doc) {
        Map<Element, Integer> lengths = new IdentityHashMap<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
            }

            @Override
            public void tail(Node node, int depth) {
                if (!(node instanceof Element el)) {
                    return;
                }
                String tag = el.normalName();
                if (SKIP_TAGS.contains(tag) && !tag.equals("picture")) {
                    lengths.put(el, 0);
                    return;
                }
                int sum = tag.equals("img") ? 1 : 0;
                for (Node child : el.childNodes()) {
                    if (child instanceof TextNode t) {
                        sum += t.isBlank() ? 0 : t.text().trim().length();
                    } else if (child instanceof Element c) {
                        sum += lengths.getOrDefault(c, 0);
                    }
                }
                lengths.put(el, sum);
            }
        }, doc);
        return lengths;
    }

    static boolean isHidden(Element el) {
        if (el.hasAttr("hidden") || "hidden".equalsIgnoreCase(el.attr("type"))) {
            return true;
        }
        if ("true".equalsIgnoreCase(el.attr("aria-hidden")) && !el.normalName().equals("svg")) {
            return true;
        }
        String style = el.attr("style");
        if (!style.isEmpty() && HIDDEN_STYLE.matcher(style.toLowerCase(Locale.ROOT)).find()) {
            return true;
        }
        return hasHiddenClass(el);
    }

    /**
     * Bootstrap "d-none" / Tailwind "hidden", unless a responsive variant shows the element again on
     * desktop ("d-none d-md-block", "hidden md:flex").
     */
    private static boolean hasHiddenClass(Element el) {
        Set<String> classes = el.classNames();
        if (!classes.contains("d-none") && !classes.contains("hidden")) {
            return false;
        }
        for (String c : classes) {
            if (RESPONSIVE_DISPLAY.matcher(c).matches()) {
                return false;
            }
        }
        return true;
    }

    static String normalize(String text) {
        return text == null ? "" : WS.matcher(text.replace(' ', ' ')).replaceAll(" ").trim();
    }

    // ------------------------------------------------------------------ rendering

    private static final class Renderer {
        private final Options opt;
        private final Set<Element> anchorPath;
        private final Element anchor;
        private final Map<Element, Integer> textLength;
        private final StringBuilder out = new StringBuilder(64 * 1024);
        private final Map<Integer, Element> nodes = new LinkedHashMap<>();
        private int nextId = 1;

        Renderer(Options opt, Set<Element> anchorPath, Element anchor, Map<Element, Integer> textLength) {
            this.opt = opt;
            this.anchorPath = anchorPath;
            this.anchor = anchor;
            this.textLength = textLength;
        }

        int length() {
            return out.length();
        }

        void truncate(int maxChars) {
            if (out.length() > maxChars) {
                out.setLength(maxChars);
                out.append("\n<!-- truncated -->");
            }
        }

        SimplifiedPage toPage(Document doc, String hint, Element anchorEl, Element focus, int originalChars) {
            return new SimplifiedPage(doc, out.toString(), Map.copyOf(nodes), hint, anchorEl, focus, originalChars);
        }

        void render(Element root) {
            if (root.normalName().equals("body")) {
                renderChildren(root);
            } else {
                renderElement(root);
            }
        }

        private boolean skip(Element el) {
            String tag = el.normalName();
            if (SKIP_TAGS.contains(tag) || isHidden(el)) {
                return true;
            }
            if (textLength.getOrDefault(el, 0) == 0 && !tag.equals("svg")) {
                return true;
            }
            if (anchorPath.contains(el)) {
                return false;
            }
            if (CHROME_TAGS.contains(tag) || CHROME_ROLES.contains(el.attr("role").toLowerCase(Locale.ROOT))) {
                return true;
            }
            for (String c : el.classNames()) {
                if (CHROME_CLASS.matcher(c.toLowerCase(Locale.ROOT)).find()) {
                    return true;
                }
            }
            return false;
        }

        private List<Element> renderableChildren(Element el) {
            List<Element> result = new ArrayList<>();
            for (Element c : el.children()) {
                if (!skip(c)) {
                    result.add(c);
                }
            }
            return result;
        }

        private void renderElement(Element el) {
            if (skip(el)) {
                return;
            }
            String tag = el.normalName();
            if (tag.equals("svg")) {
                renderSvg(el);
                return;
            }
            if (INLINE_UNWRAP.contains(tag) && el.attributesSize() == 0 && insideRunningText(el)) {
                renderChildren(el);
                return;
            }
            if (isPassThrough(el)) {
                renderChildren(el);
                return;
            }

            int id = nextId++;
            nodes.put(id, el);
            out.append('<').append(tag).append(" x=\"").append(id).append('"');
            appendAttributes(el);
            if (tag.equals("img")) {
                out.append("/>\n");
                return;
            }
            out.append('>');
            int before = out.length();
            renderChildren(el);
            boolean block = out.indexOf("\n", before) >= 0;
            if (block && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append("</").append(tag).append(">\n");
        }

        /**
         * Formatting inside a sentence ("Lương <b>15 triệu</b>/tháng") is flattened; a bare <strong> standing as
         * a value next to a label (<li><span>Kinh nghiệm:</span><strong>2 năm</strong></li>) keeps its own id.
         */
        private boolean insideRunningText(Element el) {
            Element parent = el.parent();
            return parent != null && parent.textNodes().stream().anyMatch(t -> !t.isBlank());
        }

        /** Wrapper without own text and with exactly one meaningful child: print the child only. */
        private boolean isPassThrough(Element el) {
            if (el == anchor || NO_PASS_THROUGH.contains(el.normalName()) || el.id().length() > 0) {
                return false;
            }
            for (TextNode t : el.textNodes()) {
                if (!t.isBlank()) {
                    return false;
                }
            }
            return renderableChildren(el).size() == 1;
        }

        /**
         * Renders children, collapsing long runs of similar siblings. A short run of small siblings is kept
         * whole because it is typically a label/value grid ("Kinh nghiệm: 2 năm", "Hạn nộp: ...") where every
         * item matters; long runs (menus) or runs of big items (related-job cards) are collapsed.
         */
        private void renderChildren(Element el) {
            Map<Element, int[]> runInfo = runInfo(el); // element -> {position in run (1-based), run length}
            int skipped = 0;
            String skippedSignature = null;
            for (Node child : el.childNodes()) {
                if (child instanceof TextNode t) {
                    if (!t.isBlank()) {
                        appendText(t.text());
                    }
                    continue;
                }
                if (!(child instanceof Element c) || skip(c)) {
                    continue;
                }
                int[] info = runInfo.get(c);
                if (info[0] == 1) {
                    flushSkipped(skipped, skippedSignature);
                    skipped = 0;
                }
                if (shouldCollapse(c, info[0], info[1])) {
                    skipped++;
                    skippedSignature = signature(c);
                    continue;
                }
                renderElement(c);
            }
            flushSkipped(skipped, skippedSignature);
        }

        private Map<Element, int[]> runInfo(Element el) {
            List<Element> children = renderableChildren(el);
            Map<Element, int[]> info = new IdentityHashMap<>();
            int start = 0;
            while (start < children.size()) {
                String signature = signature(children.get(start));
                int end = start + 1;
                while (end < children.size() && signature(children.get(end)).equals(signature)) {
                    end++;
                }
                for (int i = start; i < end; i++) {
                    info.put(children.get(i), new int[]{i - start + 1, end - start});
                }
                start = end;
            }
            return info;
        }

        private boolean shouldCollapse(Element el, int position, int runLength) {
            if (position <= opt.maxRepeatedSiblings() || anchorPath.contains(el)) {
                return false;
            }
            if (runLength > LONG_RUN) {
                return true;
            }
            // Big items are collapsed only when they are link cards (related jobs, menus): such a card starts with
            // its title link. Content sections of utility-class sites (Tailwind) share identical classes but start
            // with a plain heading ("Thông tin chung"), even when they contain links (industry tags), so they stay.
            return el.getAllElements().size() > BIG_ITEM_ELEMENTS && startsWithLink(el);
        }

        private boolean startsWithLink(Element el) {
            if (el.normalName().equals("a")) {
                return true;
            }
            for (TextNode t : el.select("*").stream().flatMap(e -> e.textNodes().stream()).toList()) {
                if (t.isBlank()) {
                    continue;
                }
                for (Node p = t.parent(); p != null && p != el; p = p.parent()) {
                    if (p instanceof Element pe && pe.normalName().equals("a")) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        private void flushSkipped(int skipped, String signature) {
            if (skipped > 0) {
                out.append("<!-- +").append(skipped).append(" similar ").append(signature).append(" -->\n");
            }
        }

        private String signature(Element el) {
            List<String> classes = new ArrayList<>(el.classNames());
            Collections.sort(classes);
            return classes.isEmpty() ? el.normalName() : el.normalName() + "." + String.join(".", classes);
        }

        private void appendText(String raw) {
            String text = normalize(raw);
            if (text.isEmpty()) {
                return;
            }
            if (text.length() > opt.maxTextLength()) {
                int cut = opt.maxTextLength();
                text = text.substring(0, cut) + "…(+" + (text.length() - cut) + " chars)";
            }
            if (out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                if (last != '>' && last != '\n' && last != ' ') {
                    out.append(' ');
                }
            }
            out.append(escapeText(text));
        }

        private void appendAttributes(Element el) {
            String id = el.id();
            if (!id.isEmpty() && id.length() <= 60) {
                attr("id", id);
            }
            java.util.Set<String> all = el.classNames();
            List<String> classes = all.stream()
                    .filter(c -> !GeneratedClasses.isGenerated(c, all))
                    .filter(HtmlSimplifier::isReadableClass)
                    .limit(4)
                    .toList();
            if (!classes.isEmpty()) {
                attr("class", String.join(" ", classes));
            }
            for (String name : List.of("itemprop", "role", "aria-label", "title", "alt", "name", "data-testid")) {
                String value = el.attr(name);
                if (!value.isBlank()) {
                    attr(name, shorten(normalize(value), 80));
                }
            }
            if (el.normalName().equals("img")) {
                String src = el.attr("src");
                if (src.isBlank() || src.startsWith("data:")) {
                    for (String lazy : List.of("data-src", "data-original", "data-lazy-src")) {
                        if (!el.attr(lazy).isBlank()) {
                            attr(lazy, shorten(el.attr(lazy), 100));
                            break;
                        }
                    }
                } else {
                    attr("src", shorten(src, 100));
                }
            }
        }

        private void attr(String name, String value) {
            out.append(' ').append(name).append("=\"").append(value.replace("\"", "'")).append('"');
        }

        private void renderSvg(Element svg) {
            String icon = "";
            Element use = svg.selectFirst("use");
            if (use != null) {
                String href = use.hasAttr("href") ? use.attr("href") : use.attr("xlink:href");
                int hash = href.lastIndexOf('#');
                icon = hash >= 0 ? href.substring(hash + 1) : href;
            }
            if (icon.isBlank()) {
                icon = svg.attr("aria-label");
            }
            if (icon.isBlank()) {
                icon = svg.classNames().stream().filter(HtmlSimplifier::isReadableClass).findFirst().orElse("");
            }
            if (!icon.isBlank() && !UTILITY_ICON.matcher(icon).matches()) {
                out.append("<svg icon=\"").append(shorten(icon, 40).replace("\"", "'")).append("\"/>");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final Pattern HASHED_CLASS = Pattern.compile(
            "^(css|sc|jsx|emotion|styled|tw)-[a-z0-9]{4,}$|^[a-z]{1,3}[0-9][a-z0-9]{3,}$|.*__(?=[a-z]*[0-9])[a-z0-9]{5,}$",
            Pattern.CASE_INSENSITIVE);

    /** Hides utility/hashed class tokens (tailwind variants, css-in-js hashes) to save tokens. */
    static boolean isReadableClass(String c) {
        if (c.isBlank() || c.length() > 40) {
            return false;
        }
        if (c.contains(":") || c.contains("[") || c.contains("/") || c.contains("(")) {
            return false;
        }
        return !HASHED_CLASS.matcher(c).matches();
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String escapeText(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }
}
