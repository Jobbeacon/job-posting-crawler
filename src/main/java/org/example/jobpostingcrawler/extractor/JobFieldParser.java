package org.example.jobpostingcrawler.extractor;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw job field texts into the typed values of {@link org.example.jobpostingcrawler.model.JobPosting}.
 * Shared by the JSON-LD extractor (values from schema.org JobPosting) and the HTML extractor (texts read with
 * XPath, e.g. "20 - 35 triệu", "Hạn nộp: 29/11/2026", "2 năm").
 */
@Slf4j
public final class JobFieldParser {

    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Parsed salary; every part is nullable. */
    public record Salary(Long min, Long max, String currency, String period) {
    }

    private static final Pattern SALARY_NUMBER = Pattern.compile(
            "(\\d[\\d.,]*)(?:\\s*(triệu|tr|million|mil|m|k|nghìn|ngàn)(?!\\p{L}))?");
    private static final Pattern SALARY_DECIMAL = Pattern.compile("\\d+[.,]\\d{1,2}");
    private static final Pattern VND_SALARY = Pattern.compile(
            "₫|vnd|vnđ|đồng|\\d\\s*(?:đ|triệu|tr)(?!\\p{L})");
    private static final Pattern USD_SALARY = Pattern.compile("\\$|usd(?!\\p{L})");

    /** dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy (Vietnamese boards), anywhere in the text. */
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("(?<!\\d)(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})(?!\\d)");
    /** "8 giờ trước", "3 ngày trước", "2 days ago". */
    private static final Pattern RELATIVE_PAST = Pattern.compile(
            "(\\d+)\\s*(phút|giờ|tiếng|ngày|tuần|tháng|năm|minutes?|mins?|hours?|days?|weeks?|months?|years?)\\s*(trước|ago)");
    /** "Còn 29 ngày", "Hết hạn trong 12 ngày" (VietnamWorks), "26 days left". */
    private static final Pattern RELATIVE_FUTURE = Pattern.compile(
            "(?:còn|trong)\\s*(\\d+)\\s*(ngày|tuần|tháng)|(\\d+)\\s*(days?|weeks?|months?)\\s*(left|remaining)");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern COMPACT_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");

    private JobFieldParser() {
    }

    // ------------------------------------------------------------------ salary

    /**
     * @param text         "20 - 35 triệu", "8 - 15 triệu VNĐ", "Up to 2000 USD", "Thỏa thuận"
     * @param currencyHint currency given separately (JSON-LD baseSalary.currency), nullable
     * @param unitText     period given separately (JSON-LD unitText, e.g. MONTH), nullable
     */
    public static Salary parseSalary(String text, String currencyHint, String unitText) {
        List<Long> range = parseSalaryRange(text);
        Long min = range.isEmpty() ? null : range.get(0);
        Long max = range.size() < 2 ? min : range.get(1);
        return new Salary(min, max, resolveCurrency(text, currencyHint), parseSalaryPeriod(text, unitText));
    }

    public static List<Long> parseSalaryRange(String value) {
        if (value == null) {
            return List.of();
        }
        Matcher matcher = SALARY_NUMBER.matcher(normalize(value));
        List<String> numbers = new ArrayList<>(2);
        List<Long> multipliers = new ArrayList<>(2);
        Long rangeMultiplier = null;
        while (matcher.find() && numbers.size() < 2) {
            Long multiplier = salaryMultiplier(matcher.group(2));
            numbers.add(matcher.group(1));
            multipliers.add(multiplier);
            if (multiplier != null) {
                rangeMultiplier = multiplier;
            }
        }

        List<Long> result = new ArrayList<>(2);
        for (int i = 0; i < numbers.size(); i++) {
            Long multiplier = multipliers.get(i) != null ? multipliers.get(i) : rangeMultiplier;
            try {
                result.add(multiplier == null
                        ? Long.parseLong(numbers.get(i).replace(",", "").replace(".", ""))
                        : parseScaledAmount(numbers.get(i), multiplier));
            } catch (NumberFormatException exception) {
                log.warn("Invalid salary value: {}", numbers.get(i));
            }
        }
        return result;
    }

    /** VND when the text says so; otherwise the given currency; otherwise USD when the text says so. */
    public static String resolveCurrency(String salaryText, String currency) {
        if (salaryText != null && VND_SALARY.matcher(normalize(salaryText)).find()) {
            return "VND";
        }
        if (currency != null) {
            return currency;
        }
        if (salaryText != null && USD_SALARY.matcher(normalize(salaryText)).find()) {
            return "USD";
        }
        return null;
    }

    public static String parseSalaryPeriod(String salaryText, String unitText) {
        if (salaryText != null) {
            String normalized = normalize(salaryText);
            if (normalized.contains("/tháng") || normalized.contains("/month")) {
                return "MONTH";
            }
            if (normalized.contains("/năm") || normalized.contains("/year")) {
                return "YEAR";
            }
            if (normalized.contains("/ngày") || normalized.contains("/day")) {
                return "DAY";
            }
            if (normalized.contains("/giờ") || normalized.contains("/hour")) {
                return "HOUR";
            }
        }
        return unitText;
    }

    private static Long salaryMultiplier(String unit) {
        if (unit == null) {
            return null;
        }
        return unit.startsWith("tr") || unit.startsWith("m") ? 1_000_000L : 1_000L;
    }

    private static long parseScaledAmount(String number, long multiplier) {
        String plain = SALARY_DECIMAL.matcher(number).matches()
                ? number.replace(",", ".")
                : number.replace(",", "").replace(".", "");
        return new BigDecimal(plain).multiply(BigDecimal.valueOf(multiplier)).longValue();
    }

    // ------------------------------------------------------------------ experience

    /**
     * "2 năm" -> 24, "2 - 3 Năm" -> 24, "Trên 5 Năm" -> 60, "5 years" -> 60, "6 tháng" -> 6,
     * "Không yêu cầu" -> 0. The first number is the minimum requirement.
     */
    public static int parseExperienceMonths(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        int amount = Integer.parseInt(matcher.group());
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("year") || normalized.contains("năm") ? amount * 12 : amount;
    }

    // ------------------------------------------------------------------ dates

    public static LocalDateTime parseDate(String value) {
        return parseDate(value, LocalDateTime.now(VIETNAM_ZONE));
    }

    /**
     * ISO forms (JSON-LD), then dd/MM/yyyy found anywhere in the text ("Hạn nộp: 22/11/2026",
     * "12/12/2026 (Còn 29 ngày)"), then relative forms ("8 giờ trước", "Còn 29 ngày", "26 days left").
     *
     * @param now reference time for relative forms
     */
    public static LocalDateTime parseDate(String value, LocalDateTime now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        LocalDateTime iso = parseIsoDate(trimmed);
        if (iso != null) {
            return iso;
        }
        Matcher dmy = DAY_MONTH_YEAR.matcher(trimmed);
        if (dmy.find()) {
            try {
                return LocalDate.of(Integer.parseInt(dmy.group(3)), Integer.parseInt(dmy.group(2)),
                        Integer.parseInt(dmy.group(1))).atStartOfDay();
            } catch (RuntimeException e) {
                log.warn("Invalid job posting date: {}", value);
                return null;
            }
        }
        String normalized = normalize(trimmed);
        Matcher past = RELATIVE_PAST.matcher(normalized);
        if (past.find()) {
            return shift(now, Integer.parseInt(past.group(1)), past.group(2), -1);
        }
        Matcher future = RELATIVE_FUTURE.matcher(normalized);
        if (future.find()) {
            boolean vietnamese = future.group(1) != null;
            return shift(now, Integer.parseInt(vietnamese ? future.group(1) : future.group(3)),
                    vietnamese ? future.group(2) : future.group(4), 1).toLocalDate().atStartOfDay();
        }
        if (normalized.contains("hôm nay") || normalized.equals("today")) {
            return now.toLocalDate().atStartOfDay();
        }
        log.warn("Invalid job posting date: {}", value);
        return null;
    }

    private static LocalDateTime parseIsoDate(String value) {
        try {
            // "+0700" (JobsGO) is not ISO-8601 extended: normalise to "+07:00"
            String offsetFixed = COMPACT_OFFSET.matcher(value).replaceFirst("$1:$2");
            return OffsetDateTime.parse(offsetFixed).atZoneSameInstant(VIETNAM_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), VIETNAM_ZONE);
        } catch (DateTimeParseException ignored) {
            // next format
        }
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Deadline shown as a day ("Hạn nộp: 29/11/2026") means "until the end of that day": a date without time
     * becomes 23:59:59 so the job is not considered expired a day early.
     */
    public static LocalDateTime parseDeadline(String value) {
        LocalDateTime date = parseDate(value);
        if (date == null || !date.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
            return date;
        }
        return date.toLocalDate().atTime(23, 59, 59);
    }

    private static LocalDateTime shift(LocalDateTime now, int amount, String unit, int sign) {
        long n = (long) amount * sign;
        if (unit.startsWith("phút") || unit.startsWith("min")) {
            return now.plusMinutes(n);
        }
        if (unit.startsWith("giờ") || unit.startsWith("tiếng") || unit.startsWith("hour")) {
            return now.plusHours(n);
        }
        if (unit.startsWith("ngày") || unit.startsWith("day")) {
            return now.plusDays(n);
        }
        if (unit.startsWith("tuần") || unit.startsWith("week")) {
            return now.plusWeeks(n);
        }
        if (unit.startsWith("tháng") || unit.startsWith("month")) {
            return now.plusMonths(n);
        }
        return now.plusYears(n);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
