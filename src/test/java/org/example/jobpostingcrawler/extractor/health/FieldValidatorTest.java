package org.example.jobpostingcrawler.extractor.health;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldValidatorTest {

    @Test
    void valuesOfTheRightShapeAreValid() {
        assertTrue(FieldValidator.isValid("title", "Kế Toán Tổng Hợp", "Công ty ABC"));
        assertTrue(FieldValidator.isValid("salary", "20 - 35 triệu", null));
        assertTrue(FieldValidator.isValid("salary", "Thỏa thuận", null));
        assertTrue(FieldValidator.isValid("salary", "Sign in to view salary", null));
        assertTrue(FieldValidator.isValid("experienceMonths", "2 năm", null));
        assertTrue(FieldValidator.isValid("experienceMonths", "Không yêu cầu", null));
        assertTrue(FieldValidator.isValid("expiredDate", "Hạn nộp: 29/11/2026", null));
        assertTrue(FieldValidator.isValid("expiredDate", "Hết hạn trong 12 ngày", null));
        assertTrue(FieldValidator.isValid("postedDate", "12/11/2026", null));
        assertTrue(FieldValidator.isValid("companyLogoUrl", "https://cdn.topcv.vn/logo.png", null));
        assertTrue(FieldValidator.isValid("location", "Hà Nội", null));
        assertTrue(FieldValidator.isValid("skills", List.of("Java", "Spring", "SQL"), null));
    }

    @Test
    void emptyOrWrongValuesAreInvalid() {
        assertFalse(FieldValidator.isValid("location", null, null));
        assertFalse(FieldValidator.isValid("location", "  ", null));
        assertFalse(FieldValidator.isValid("salary", "Nhân viên", null), "job level instead of salary");
        assertFalse(FieldValidator.isValid("expiredDate", "Toàn thời gian", null));
        assertFalse(FieldValidator.isValid("experienceMonths", "Toàn thời gian", null));
        assertFalse(FieldValidator.isValid("location", "x".repeat(400), null), "a whole section");
        assertFalse(FieldValidator.isValid("description", "Mô tả", null));
        assertFalse(FieldValidator.isValid("title", "Công ty TNHH ABC", "CÔNG TY TNHH ABC"), "company as title");
        assertFalse(FieldValidator.isValid("companyLogoUrl", "data:image/gif;base64,R0lG", null));
        assertFalse(FieldValidator.isValid("skills", List.of(), null));
        assertFalse(FieldValidator.isValid("skills", List.of("Yêu cầu: 1 năm kinh nghiệm", "Quyền lợi: BHXH"), null));
    }

    @Test
    void jsonLdAndHtmlTextsAreComparedLoosely() {
        assertTrue(FieldValidator.similar("CÔNG TY CỔ PHẦN BPO MẮT BÃO", "Công ty Cổ phần BPO Mắt Bão"));
        assertTrue(FieldValidator.similar("Kế Toán Tổng Hợp - Hà Nội", "Kế toán tổng hợp"));
        assertTrue(FieldValidator.similar("Senior Java Developer (Spring, AWS)", "Senior Java Developer – Spring/AWS"));
        assertFalse(FieldValidator.similar("Nhân Viên Kinh Doanh", "Kế Toán Tổng Hợp"));
        assertFalse(FieldValidator.similar("Việc làm liên quan", "Senior Java Developer"));
    }
}
