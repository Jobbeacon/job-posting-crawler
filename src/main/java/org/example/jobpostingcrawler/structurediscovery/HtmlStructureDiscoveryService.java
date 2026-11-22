package org.example.jobpostingcrawler.structurediscovery;

import lombok.extern.slf4j.Slf4j;
import org.example.jobpostingcrawler.structurediscovery.llm.LlmSelection;
import org.example.jobpostingcrawler.structurediscovery.llm.StructureLlmClient;
import org.example.jobpostingcrawler.structurediscovery.page.PageLoader;
import org.example.jobpostingcrawler.structurediscovery.page.SampleUrlCollector;
import org.example.jobpostingcrawler.structurediscovery.simplify.HtmlSimplifier;
import org.example.jobpostingcrawler.structurediscovery.simplify.SimplifiedPage;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathCandidate;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathCandidateGenerator;
import org.example.jobpostingcrawler.structurediscovery.xpath.XPathEvaluator;
import org.example.jobpostingcrawler.utils.UrlUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Discovers the HTML structure (XPath per field) of a job board domain.
 *
 * <pre>
 *  sample job pages ──► HtmlSimplifier ──► LLM picks node ids per field (on llmSampleCount pages)
 *                                               │
 *                                               ▼
 *                         XPathCandidateGenerator (real DOM, validated on its own page)
 *                                               │
 *                                               ▼
 *        score every candidate on ALL samples: agreement with the LLM's independent picks,
 *        coverage (non-empty), single match, sane length ──► best XPath per field
 * </pre>
 *
 * Standalone: not used by the crawl pipeline.
 */
@Slf4j
public class HtmlStructureDiscoveryService {

    /** One page used for discovery. */
    public record SamplePage(String url, Document document) {
    }

    private static final int MAX_SHORT_VALUE = 300;
    private static final int MAX_REPORTED_VALUE = 160;
    private static final String EMPTY = "<empty>";
    private static final int MAX_CAPTION_LENGTH = 40;
    private static final String NOT_JOB_SUFFIX = " is not a job posting, skipped";
    /** URL shape groups tried in turn when the LLM rejects the pages of a group as non job postings. */
    private static final int MAX_URL_GROUPS = 3;
    /** Listing pages loaded when the seeds link no job page. */
    private static final int MAX_LISTING_PAGES = 3;
    /** The literal that follows is a class name: {@code contains(@class, '} or {@code @class='}. */
    private static final Pattern CLASS_ARGUMENT = Pattern.compile("@class\\s*(,|=)\\s*$");
    private static final Pattern LEVEL_CAPTION = Pattern.compile(
            "'\\s*(cấp bậc|cap bac|level|job level|rank|vị trí|chức vụ)\\s*:?\\s*'",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern JOB_LEVEL = Pattern.compile(
            "nhân viên|trưởng phòng|trưởng nhóm|quản lý|giám đốc|phó giám đốc|chuyên viên|chuyên gia|thực tập sinh"
                    + "|\\b(staff|senior|junior|middle|fresher|intern|lead|leader|manager|director|head|executive"
                    + "|supervisor|specialist|officer|associate)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WORKING_FORM = Pattern.compile(
            "thời gian|toàn thời|bán thời|làm từ xa|tại văn phòng|hợp đồng|thời vụ|cộng tác|\\b(full[- ]?time"
                    + "|part[- ]?time|remote|hybrid|on[- ]?site|at office|contract|freelance|temporary|seasonal)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** "Yêu cầu: ...", "Job Domain: ..." (caption of 1-4 words then a colon) at the start of a list item. */
    private static final Pattern LABELLED_ROW = Pattern.compile("^\\s*[^\\s:,]+(\\s[^\\s:,]+){0,3}\\s?:\\s");
    private static final Pattern REQUIREMENT_ITEM = Pattern.compile(
            "kinh nghiệm|\\d+\\s*năm|tuổi|trở lên|đại học|cao đẳng|trung cấp|trung học|tốt nghiệp|bằng cấp"
                    + "|^\\s*(nam|nữ|nam/nữ)\\s*$|years? of experience|\\bdegree\\b|bachelor|\\bage\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Candidates at or above this priority are positional / style-class based. */
    private static final int FRAGILE_PRIORITY = 6;

    private final StructureLlmClient llm;
    private final HtmlSimplifier simplifier;
    private final XPathCandidateGenerator generator = new XPathCandidateGenerator();
    private final int llmSampleCount;

    public HtmlStructureDiscoveryService(StructureLlmClient llm, HtmlSimplifier simplifier, int llmSampleCount) {
        this.llm = llm;
        this.simplifier = simplifier;
        this.llmSampleCount = Math.max(1, llmSampleCount);
    }

    /**
     * Full flow: loads the seed pages, picks job detail links matching the prefix, loads them and discovers.
     * <ol>
     *   <li>Links are grouped by URL shape, the biggest group is tried first; when the LLM says none of its pages
     *   is a job posting, the next group is tried.</li>
     *   <li>When the seeds yield no job page at all (a homepage whose job list is loaded later, linking only
     *   company pages), a few listing pages linked from the seeds (/tim-viec-lam...) are loaded and used the
     *   same way.</li>
     * </ol>
     *
     * @param explicitSampleUrls job detail URLs to use instead of collecting them from the seeds (nullable)
     */
    public DiscoveryResult discover(PageLoader loader, List<String> seedUrls, String jobPageUrlPrefix,
                                    List<String> explicitSampleUrls, int sampleCount) {
        List<String> warnings = new ArrayList<>();
        Set<String> triedShapes = new HashSet<>();
        DiscoveryResult result;
        if (explicitSampleUrls != null && !explicitSampleUrls.isEmpty()) {
            result = tryGroups(loader, List.of(explicitSampleUrls), warnings);
        } else {
            List<Document> seeds = loadAll(loader, seedUrls, "seed", warnings);
            result = tryGroups(loader, groups(seeds, jobPageUrlPrefix, sampleCount, triedShapes), warnings);
            if (result == null || onlyNonJobPages(result)) {
                List<String> listings = new ArrayList<>();
                seeds.forEach(seed -> SampleUrlCollector.listingPages(seed, MAX_LISTING_PAGES).stream()
                        .filter(u -> !listings.contains(u) && listings.size() < MAX_LISTING_PAGES)
                        .forEach(listings::add));
                if (!listings.isEmpty()) {
                    log.info("[StructureDiscovery] no job page linked from the seeds, trying listing pages {}",
                            listings);
                    DiscoveryResult fromListings = tryGroups(loader, groups(loadAll(loader, listings, "listing",
                            warnings), jobPageUrlPrefix, sampleCount, triedShapes), warnings);
                    result = fromListings != null ? fromListings : result;
                }
            }
        }
        if (result == null) {
            throw new IllegalStateException("No job page found to sample (seeds=" + seedUrls + ", prefix="
                    + jobPageUrlPrefix + "). " + warnings);
        }
        // warnings of the groups tried before the final one, then the final one (already listed when it failed)
        List<String> all = new ArrayList<>(warnings);
        if (!onlyNonJobPages(result)) {
            all.addAll(result.warnings());
        }
        return new DiscoveryResult(result.domain(), result.domainKey(), result.sampleUrls(), result.fields(), all);
    }

    private static List<Document> loadAll(PageLoader loader, List<String> urls, String what, List<String> warnings) {
        List<Document> docs = new ArrayList<>();
        for (String url : urls == null ? List.<String>of() : urls) {
            try {
                docs.add(loader.load(url));
            } catch (Exception e) {
                warnings.add("Cannot load " + what + " " + url + ": " + e.getMessage());
            }
        }
        return docs;
    }

    /** URL groups of the pages (merged by shape), biggest first, skipping shapes already tried. */
    private static List<List<String>> groups(List<Document> pages, String prefix, int sampleCount,
                                             Set<String> triedShapes) {
        Map<String, List<String>> byShape = new LinkedHashMap<>();
        for (Document page : pages) {
            SampleUrlCollector.groups(page, prefix).forEach((shape, urls) -> urls.forEach(u -> {
                List<String> group = byShape.computeIfAbsent(shape, k -> new ArrayList<>());
                if (!group.contains(u)) {
                    group.add(u);
                }
            }));
        }
        List<List<String>> groups = new ArrayList<>();
        SampleUrlCollector.bySize(byShape).entrySet().stream()
                .filter(e -> triedShapes.add(e.getKey()))
                .limit(MAX_URL_GROUPS)
                .forEach(e -> groups.add(SampleUrlCollector.spread(e.getValue(), sampleCount)));
        return groups;
    }

    /**
     * Discovers on each group in turn until one holds job postings.
     *
     * @return the first result with job postings, else the last one (null when no page could be loaded); the
     * warnings of rejected groups are added to {@code warnings}
     */
    private DiscoveryResult tryGroups(PageLoader loader, List<List<String>> groups, List<String> warnings) {
        DiscoveryResult result = null;
        for (List<String> urls : groups) {
            List<SamplePage> samples = new ArrayList<>();
            for (String url : urls) {
                try {
                    samples.add(new SamplePage(url, loader.load(url)));
                } catch (Exception e) {
                    warnings.add("Cannot load sample " + url + ": " + e.getMessage());
                }
            }
            if (samples.isEmpty()) {
                continue;
            }
            result = discover(UrlUtils.extractDomain(urls.get(0)), samples);
            if (!onlyNonJobPages(result)) {
                return result;
            }
            warnings.addAll(result.warnings());
            log.info("[StructureDiscovery] no job posting among {}..., trying the next URL group", urls.get(0));
        }
        return result;
    }

    /** Nothing found because the LLM rejected every analysed page as "not a job posting". */
    private static boolean onlyNonJobPages(DiscoveryResult result) {
        return result.fields().isEmpty() && result.warnings().stream().anyMatch(w -> w.endsWith(NOT_JOB_SUFFIX))
                && result.warnings().stream().noneMatch(w -> w.startsWith("LLM failed"));
    }

    /** Core flow on already loaded pages (testable without network). */
    public DiscoveryResult discover(String domain, List<SamplePage> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("At least one sample page is required");
        }
        List<String> warnings = new ArrayList<>();

        // 1) LLM picks on the first llmSampleCount pages that really are job postings
        List<Analysis> analyses = new ArrayList<>();
        int nonJobPages = 0;
        for (SamplePage sample : samples) {
            // stop when enough pages were analysed, or when the first pages are all rejected (wrong URL group)
            if (analyses.size() >= llmSampleCount || (analyses.isEmpty() && nonJobPages >= llmSampleCount)) {
                break;
            }
            SimplifiedPage page = simplifier.simplify(sample.document());
            log.info("[StructureDiscovery] {} simplified: {}", sample.url(), page.stats());
            LlmSelection selection;
            try {
                selection = llm.select(page, sample.url());
            } catch (Exception e) {
                warnings.add("LLM failed on " + sample.url() + ": " + e.getMessage());
                continue;
            }
            if (!selection.jobPosting()) {
                nonJobPages++;
                warnings.add("LLM says " + sample.url() + NOT_JOB_SUFFIX);
                continue;
            }
            analyses.add(new Analysis(sample, page, selection));
        }
        if (analyses.isEmpty()) {
            warnings.add("No sample could be analysed by the LLM");
            return new DiscoveryResult(domain, domainKey(domain), urls(samples), Map.of(), warnings);
        }

        // 2) candidates per field, 3) cross-sample scoring
        Map<JobField, DiscoveryResult.FieldResult> fields = new EnumMap<>(JobField.class);
        for (JobField field : JobField.values()) {
            DiscoveryResult.FieldResult result = discoverField(field, analyses, samples, fields, warnings);
            if (result != null) {
                fields.put(field, result);
            } else if (field.required()) {
                warnings.add("Required field '" + field.yamlKey() + "' was not found");
            }
        }
        return new DiscoveryResult(domain, domainKey(domain), urls(samples), fields, warnings);
    }

    /**
     * @param found fields located so far (enum order: industry is found before skills)
     */
    private DiscoveryResult.FieldResult discoverField(JobField field, List<Analysis> analyses,
                                                      List<SamplePage> samples,
                                                      Map<JobField, DiscoveryResult.FieldResult> found,
                                                      List<String> warnings) {
        Map<String, XPathCandidate> pool = new LinkedHashMap<>();
        Map<Document, Element> llmTargets = new IdentityHashMap<>();
        for (Analysis a : analyses) {
            LlmSelection.FieldPick pick = a.selection().picks().get(field);
            if (pick == null) {
                continue;
            }
            Element node = a.page().node(pick.node());
            if (node == null) {
                warnings.add("LLM returned unknown node x=" + pick.node() + " for '" + field.yamlKey() + "' on "
                        + a.sample().url());
                continue;
            }
            node = valueContainer(field, node, pick.label());
            Element target = normalizeTarget(field, node);
            if (target == null) {
                continue;
            }
            llmTargets.put(a.sample().document(), target);
            for (XPathCandidate c : generator.generate(a.sample().document(), node, field, pick.label(), pick.xpath())) {
                pool.merge(c.xpath(), c, (x, y) -> x.priority() <= y.priority() ? x : y);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }

        List<Scored> scored = new ArrayList<>();
        for (XPathCandidate c : pool.values()) {
            Scored candidate = score(field, c, samples, llmTargets);
            if (field == JobField.SKILLS && (looksLikeRequirements(candidate.values())
                    || sameValueOnAnySample(candidate.values(), found.get(JobField.INDUSTRY)))) {
                // TopCV: the first "required-tag" block is the industries on most pages, the skills on others
                continue;
            }
            if (c.strategy().contains("llm") && dependsOnTheValue(c.xpath(), candidate.values())) {
                // LLM guess filtering on the value itself (ITviec: contains(text(), 'Ho Chi Minh')): matches the
                // samples, misses every job elsewhere
                continue;
            }
            if (field == JobField.EMPLOYMENT_TYPE && looksLikeJobLevel(c.xpath(), candidate.values())) {
                // VietnamWorks "CẤP BẬC: Nhân viên" is the job level, not full-time/part-time
                continue;
            }
            if (field.kind() == JobField.Kind.LIST && looksLikeLabelledRows(candidate.values())) {
                // a whole info box ("Yêu cầu: ...", "Quyền lợi: ...", "Chuyên môn: ...") instead of a tag list
                continue;
            }
            scored.add(candidate);
        }
        if (scored.isEmpty()) {
            if (field == JobField.SKILLS) {
                warnings.add("'skills' candidates only hold requirement tags (experience, degree, age...) or the "
                        + "industries: ignored");
            }
            return null;
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingInt(s -> s.candidate().priority())
                .thenComparingInt(s -> s.candidate().xpath().length()));

        Scored best = scored.get(0);
        if (best.coverage() == 0 || (best.agreement() == 0 && !llmTargets.isEmpty())) {
            return null;
        }
        if (best.coverage() < samples.size()) {
            best = complementWithUnion(field, best, scored, samples, llmTargets);
        }
        if (best.coverage() < samples.size()) {
            warnings.add("'" + field.yamlKey() + "' found on " + best.coverage() + "/" + samples.size()
                    + " samples only (may be optional on this site)");
        }
        if (best.candidate().priority() >= FRAGILE_PRIORITY) {
            warnings.add("'" + field.yamlKey() + "' uses a fragile " + best.candidate().strategy()
                    + " XPath (positional or style classes): check it");
        }
        if (best.agreement() < llmTargets.size()) {
            warnings.add("'" + field.yamlKey() + "' disagrees with the LLM on " + (llmTargets.size() - best.agreement())
                    + "/" + llmTargets.size() + " analysed samples");
        }
        List<String> alternatives = scored.stream().skip(1).limit(3)
                .map(s -> s.candidate().xpath()).toList();
        return new DiscoveryResult.FieldResult(field, best.candidate().xpath(), best.candidate().strategy(),
                best.coverage(), best.agreement(), llmTargets.size(), samples.size(), best.values(), alternatives);
    }

    /**
     * Sites sometimes serve several templates (JobsGO: old and new layout). When the best XPath misses some
     * samples, another candidate that covers exactly those is joined with the XPath union operator.
     */
    private Scored complementWithUnion(JobField field, Scored best, List<Scored> scored, List<SamplePage> samples,
                                       Map<Document, Element> llmTargets) {
        for (Scored other : scored) {
            if (other == best || other.agreement() == 0 || other.candidate().priority() >= FRAGILE_PRIORITY) {
                continue;
            }
            boolean coversMissing = true;
            for (int i = 0; i < samples.size(); i++) {
                if (best.values().get(i).equals(EMPTY) && other.values().get(i).equals(EMPTY)) {
                    coversMissing = false;
                    break;
                }
            }
            if (!coversMissing) {
                continue;
            }
            String union = union(best.candidate().xpath(), other.candidate().xpath());
            if (union == null) {
                continue;
            }
            Scored joined = score(field, new XPathCandidate(union,
                    best.candidate().strategy() + "|" + other.candidate().strategy(),
                    Math.max(best.candidate().priority(), other.candidate().priority())), samples, llmTargets);
            if (joined.coverage() > best.coverage() && joined.score() > best.score()) {
                return joined;
            }
        }
        return best;
    }

    /** "A | B", or "(A | B)/@src" for attribute XPaths (the extractor reads the attribute after the last "/@"). */
    static String union(String a, String b) {
        String attrA = attributeSuffix(a);
        String attrB = attributeSuffix(b);
        if (!attrA.equals(attrB)) {
            return null;
        }
        if (attrA.isEmpty()) {
            return a + " | " + b;
        }
        return "(" + a.substring(0, a.length() - attrA.length()) + " | "
                + b.substring(0, b.length() - attrB.length()) + ")" + attrA;
    }

    private static String attributeSuffix(String xpath) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/@[\\w:-]+$").matcher(xpath);
        return m.find() ? m.group() : "";
    }

    private Scored score(JobField field, XPathCandidate c, List<SamplePage> samples, Map<Document, Element> llmTargets) {
        int coverage = 0;
        int agreement = 0;
        int penalty = 0;
        List<String> values = new ArrayList<>();
        String elementXpath = c.xpath().replaceAll("/@[\\w:-]+$", "");
        for (SamplePage sample : samples) {
            XPathEvaluator.Evaluation eval = XPathEvaluator.evaluate(sample.document(), c.xpath(), field.kind());
            values.add(eval.isEmpty() ? EMPTY : shorten(eval.value()));
            if (eval.isEmpty()) {
                continue;
            }
            coverage++;
            Element expected = llmTargets.get(sample.document());
            if (expected != null) {
                Element first = field.kind() == JobField.Kind.ATTRIBUTE
                        ? XPathEvaluator.evaluate(sample.document(), elementXpath, JobField.Kind.TEXT).first()
                        : eval.first();
                if (first == expected || (first != null && !expected.text().isBlank()
                        && first.text().equals(expected.text()))) {
                    agreement++;
                } else {
                    penalty += 2; // contradicts what the LLM saw on that page
                }
            }
            if (eval.count() > 1 && field.kind() != JobField.Kind.LIST
                    && (field != JobField.LOCATION || !sameParent(eval.elements()))) {
                // values would be joined with "|". Several locations are fine when they form one list (siblings);
                // matches spread over the page are other fields sharing a class (ITviec: address|Hybrid|Posted...)
                penalty++;
            }
            if (field != JobField.DESCRIPTION && field != JobField.BENEFITS && eval.value().length() > MAX_SHORT_VALUE) {
                penalty++; // grabbed a whole block instead of the value
            }
        }
        return new Scored(c, agreement * 3 + coverage * 2 - penalty, coverage, agreement, values);
    }

    /**
     * Employment type candidates reading the job level: XPath anchored on a level caption ("Cấp bậc", "Level"),
     * or values that are levels (Nhân viên, Trưởng phòng, Senior...) and never a working form.
     */
    static boolean looksLikeJobLevel(String xpath, List<String> sampleValues) {
        if (LEVEL_CAPTION.matcher(xpath).find()) {
            return true;
        }
        int values = 0;
        int levels = 0;
        for (String value : sampleValues) {
            if (value.equals(EMPTY)) {
                continue;
            }
            if (WORKING_FORM.matcher(value).find()) {
                return false;
            }
            values++;
            if (JOB_LEVEL.matcher(value).find()) {
                levels++;
            }
        }
        return values > 0 && levels * 2 > values;
    }

    /** A string literal of the XPath is part of the value it extracts on some sample. */
    static boolean dependsOnTheValue(String xpath, List<String> sampleValues) {
        for (String raw : literals(xpath)) {
            String literal = raw.trim();
            if (literal.length() < 3) {
                continue;
            }
            for (String value : sampleValues) {
                if (!value.equals(EMPTY) && value.contains(literal)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** String literals of an XPath in order, except class names ({@code contains(@class,'x')}). */
    private static List<String> literals(String xpath) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < xpath.length()) {
            char quote = xpath.charAt(i);
            if (quote != '\'' && quote != '"') {
                i++;
                continue;
            }
            int end = xpath.indexOf(quote, i + 1);
            if (end < 0) {
                break;
            }
            if (!CLASS_ARGUMENT.matcher(xpath.substring(0, i)).find()) {
                literals.add(xpath.substring(i + 1, end));
            }
            i = end + 1;
        }
        return literals;
    }

    /** Most items start with a caption ("Yêu cầu: ...", "Quyền lợi: ..."): rows of an info box, not tags. */
    static boolean looksLikeLabelledRows(List<String> sampleValues) {
        int items = 0;
        int labelled = 0;
        for (String value : sampleValues) {
            if (value.equals(EMPTY)) {
                continue;
            }
            for (String item : value.split(", ")) {
                items++;
                if (LABELLED_ROW.matcher(item).find()) {
                    labelled++;
                }
            }
        }
        return items > 0 && labelled * 2 > items;
    }

    /** The candidate gives exactly the value of another field on some sample: it selects that field. */
    private static boolean sameValueOnAnySample(List<String> values, DiscoveryResult.FieldResult other) {
        if (other == null) {
            return false;
        }
        for (int i = 0; i < values.size() && i < other.sampleValues().size(); i++) {
            if (!values.get(i).equals(EMPTY) && values.get(i).equals(other.sampleValues().get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameParent(List<Element> elements) {
        Element parent = elements.get(0).parent();
        return parent != null && elements.stream().allMatch(e -> e.parent() == parent);
    }

    /**
     * LIST fields: the LLM sometimes picks the row holding both the caption and the values
     * ({@code <div><div>Job Domain:</div><div><a>Banking</a>...</div></div>}); the children would then start with
     * the caption. The values container is the element right after the caption.
     */
    static Element valueContainer(JobField field, Element node, String labelHint) {
        if (field.kind() != JobField.Kind.LIST || node.childrenSize() < 2) {
            return node;
        }
        Element first = node.child(0);
        String caption = first.text().trim();
        boolean isCaption = first.childrenSize() == 0 && caption.length() <= MAX_CAPTION_LENGTH
                && (caption.endsWith(":") || (labelHint != null && !labelHint.isBlank()
                && stripColon(caption).equalsIgnoreCase(stripColon(labelHint.trim()))));
        if (!isCaption) {
            return node;
        }
        Element values = first.nextElementSibling();
        return values != null && values.childrenSize() > 0 ? values : node;
    }

    private static String stripColon(String s) {
        return s.endsWith(":") ? s.substring(0, s.length() - 1).trim() : s;
    }

    /**
     * TopCV-like "Yêu cầu" tags (1 năm kinh nghiệm, Đại học trở lên, Tuổi 19 - 30, Nam) are not skills; they
     * would be merged into the job's skills. True when most items of the samples look like such requirements.
     */
    static boolean looksLikeRequirements(List<String> sampleValues) {
        int items = 0;
        int requirements = 0;
        for (String value : sampleValues) {
            if (value.equals(EMPTY)) {
                continue;
            }
            for (String item : value.split(", ")) {
                items++;
                if (REQUIREMENT_ITEM.matcher(item).find()) {
                    requirements++;
                }
            }
        }
        return items > 0 && requirements * 2 > items;
    }

    /** For logo fields the img element is what the XPath must select. */
    private static Element normalizeTarget(JobField field, Element node) {
        if (field.kind() != JobField.Kind.ATTRIBUTE || node.normalName().equals("img")) {
            return node;
        }
        return node.selectFirst("img");
    }

    public static String domainKey(String domain) {
        return domain == null ? null : domain.replace(".", "_");
    }

    private static List<String> urls(List<SamplePage> samples) {
        return samples.stream().map(SamplePage::url).toList();
    }

    private static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= MAX_REPORTED_VALUE ? one : one.substring(0, MAX_REPORTED_VALUE) + "…";
    }

    private record Analysis(SamplePage sample, SimplifiedPage page, LlmSelection selection) {
    }

    private record Scored(XPathCandidate candidate, int score, int coverage, int agreement, List<String> values) {
    }
}
