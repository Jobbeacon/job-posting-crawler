package org.example.jobpostingcrawler.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Texts below were taken from real job pages (TopCV, JobsGO, CareerViet, Vieclam24h, 123job, TopDev, ITviec). */
class JobFieldParserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 10, 0);

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "20 - 35 triệu              | 20000000 | 35000000 | VND  | null",
            "8 - 15 triệu VNĐ           | 8000000  | 15000000 | VND  | null",
            "Lương cứng từ 8 - 12 triệu đồng/tháng | 8000000 | 12000000 | VND | MONTH",
            "17 - 22 triệu VNĐ          | 17000000 | 22000000 | VND  | null",
            "Up to 2000 USD             | 2000     | 2000     | USD  | null",
            "$1,500 - $2,500            | 1500     | 2500     | USD  | null",
            "Thỏa thuận                 | null     | null     | null | null",
            "Cạnh tranh                 | null     | null     | null | null",
    })
    void parsesSalaryTexts(String text, Long min, Long max, String currency, String period) {
        JobFieldParser.Salary salary = JobFieldParser.parseSalary(text.trim(), null, null);
        assertEquals(min, salary.min());
        assertEquals(max, salary.max());
        assertEquals(currency, salary.currency());
        assertEquals(period, salary.period());
    }

    @Test
    void salaryKeepsTheSeparatelyGivenCurrencyAndPeriod() {
        JobFieldParser.Salary salary = JobFieldParser.parseSalary("1000 - 2000", "USD", "MONTH");
        assertEquals("USD", salary.currency());
        assertEquals("MONTH", salary.period());
        assertNull(JobFieldParser.parseSalary(null, null, null).min());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "1 năm                     | 12",
            "2 - 3 Năm                 | 24",
            "Trên 5 Năm                | 60",
            "5 years                   | 60",
            "6 tháng                   | 6",
            "Không yêu cầu             | 0",
            "Không yêu cầu kinh nghiệm | 0",
    })
    void parsesExperienceTexts(String text, int months) {
        assertEquals(months, JobFieldParser.parseExperienceMonths(text.trim()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "29/11/2026                      | 2026-11-29T00:00",
            "Hạn nộp: 22/11/2026             | 2026-11-22T00:00",
            "12/12/2026 (Còn 29 ngày)        | 2026-12-12T00:00",
            "13-11-2026                      | 2026-11-13T00:00",
            "2026-11-28T23:59:59             | 2026-11-28T23:59:59",
            "2026-10-29                      | 2026-10-29T00:00",
            "2026-11-12T08:38:13+0700        | 2026-11-12T08:38:13",
            "2026-12-31T23:59:00Z            | 2027-01-01T06:59",
            "Đăng: 8 giờ trước               | 2026-11-14T02:00",
            "3 ngày trước                    | 2026-11-11T10:00",
            "Còn 29 ngày                     | 2026-12-13T00:00",
            "Hết hạn trong 12 ngày           | 2026-11-26T00:00",
            "26 days left                    | 2026-12-10T00:00",
    })
    void parsesDateTexts(String text, LocalDateTime expected) {
        assertEquals(expected, JobFieldParser.parseDate(text.trim(), NOW));
    }

    @Test
    void unparseableDatesAreNull() {
        assertNull(JobFieldParser.parseDate("Hết hạn", NOW));
        assertNull(JobFieldParser.parseDate("31/02/2026", NOW));
        assertNull(JobFieldParser.parseDate(null, NOW));
    }

    @Test
    void deadlineDayMeansEndOfThatDay() {
        assertEquals(LocalDateTime.of(2026, 11, 29, 23, 59, 59), JobFieldParser.parseDeadline("Hạn ứng tuyển 29/11/2026"));
        // an explicit time is kept
        assertEquals(LocalDateTime.of(2026, 11, 28, 23, 59, 59), JobFieldParser.parseDeadline("2026-11-28T23:59:59"));
        assertNull(JobFieldParser.parseDeadline(null));
    }
}
