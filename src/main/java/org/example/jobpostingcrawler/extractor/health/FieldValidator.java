package org.example.jobpostingcrawler.extractor.health;

import org.example.jobpostingcrawler.extractor.JobFieldParser;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tells whether a value read by an XPath has the shape of its field. After a layout change an XPath usually
 * finds nothing, or another block whose text does not look like the field (a whole section as the location,
 * the job level as the date...).
 */
public final class FieldValidator {

    private static final Pattern NO_EXPERIENCE = Pattern.compile(
            "không yêu cầu|chưa có kinh nghiệm|không cần kinh nghiệm|no experience|not required|fresher",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Salary shown without numbers: still the salary field. */
    private static final Pattern SALARY_WITHOUT_AMOUNT = Pattern.compile(
            "thỏa thuận|thoả thuận|thương lượng|cạnh tranh|negotiable|competitive|sign in|đăng nhập|login",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** List item that is an info box row ("Yêu cầu: ...") rather than a tag. */
    private static final Pattern LABELLED_ROW = Pattern.compile("^\\s*[^\\s:,]+(\\s[^\\s:,]+){0,3}\\s?:\\s");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final double MIN_SHARED_WORDS = 0.6;

    private FieldValidator() {
    }

    /**
     * @param field       key of the field ("title", "salary"...)
     * @param value       raw value (String, or List of String for industry/skills); null when the XPath found
     *                    nothing
     * @param companyName raw company name of the same page, a title equal to it is the wrong element
     */
    public static boolean isValid(String field, Object value, String companyName) {
        if (value instanceof List<?> items) {
            return isValidList(items);
        }
        String text = value == null ? null : value.toString().trim();
        if (text == null || text.isEmpty()) {
            return false;
        }
        return switch (field) {
            case "title" -> between(text, 5, 200) && (companyName == null || !similar(text, companyName)
                    || text.length() > companyName.length() + 10);
            case "companyName" -> between(text, 2, 200);
            case "companyLogoUrl" -> text.startsWith("http") || text.startsWith("/");
            case "description" -> text.length() >= 100;
            case "experienceMonths" -> JobFieldParser.parseExperienceMonths(text) > 0
                    || NO_EXPERIENCE.matcher(text).find();
            case "employmentType" -> between(text, 2, 80);
            case "postedDate" -> JobFieldParser.parseDate(text) != null;
            case "expiredDate" -> JobFieldParser.parseDeadline(text) != null;
            case "location" -> between(text, 2, 300);
            case "salary" -> !JobFieldParser.parseSalaryRange(text).isEmpty()
                    || SALARY_WITHOUT_AMOUNT.matcher(text).find();
            case "benefits" -> text.length() >= 20;
            default -> true;
        };
    }

    /** 1 to 30 tags, mostly not "Caption: ..." rows. */
    private static boolean isValidList(List<?> items) {
        if (items.isEmpty() || items.size() > 30) {
            return false;
        }
        long labelled = items.stream().filter(i -> i != null && LABELLED_ROW.matcher(i.toString()).find()).count();
        return labelled * 2 <= items.size();
    }

    /**
     * Same text up to case, accents, punctuation and extra words: one contains the other, or they share at least
     * 60% of their words. JSON-LD and HTML rarely match exactly ("CÔNG TY TNHH ABC" / "Công ty TNHH ABC",
     * "Kế toán - Hà Nội" / "Kế toán").
     */
    public static boolean similar(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        if (x.contains(y) || y.contains(x)) {
            return true;
        }
        Set<String> wordsX = new HashSet<>(Arrays.asList(x.split(" ")));
        Set<String> wordsY = new HashSet<>(Arrays.asList(y.split(" ")));
        Set<String> shared = new HashSet<>(wordsX);
        shared.retainAll(wordsY);
        return shared.size() >= MIN_SHARED_WORDS * Math.min(wordsX.size(), wordsY.size());
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String noAccents = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return NOT_ALPHANUMERIC.matcher(noAccents).replaceAll(" ").trim();
    }

    private static boolean between(String text, int min, int max) {
        return text.length() >= min && text.length() <= max;
    }
}
