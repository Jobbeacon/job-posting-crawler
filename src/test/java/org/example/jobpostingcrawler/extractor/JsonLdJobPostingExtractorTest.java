package org.example.jobpostingcrawler.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.jobpostingcrawler.extractor.jsonld.JsonLdJobPostingExtractor;
import org.example.jobpostingcrawler.model.JobPosting;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonLdJobPostingExtractorTest {

    private static JobPosting extract(String baseSalary) {
        String html = "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"JobPosting\","
                + "\"title\":\"Cloud Engineer\",\"description\":\"desc\",\"baseSalary\":" + baseSalary + "}</script>";
        String url = "https://www.vietnamworks.com/cloud-1793709411048248747-2127437-jv";
        return new JsonLdJobPostingExtractor(new ObjectMapper()).extract(Jsoup.parse(html, url), url);
    }

    @Test
    void negotiableSalaryHasNoCurrencyNorPeriod() {
        // VietnamWorks declares USD per HOUR for "Thương lượng"
        JobPosting job = extract("{\"@type\":\"MonetaryAmount\",\"currency\":\"USD\",\"value\":{\"@type\":"
                + "\"QuantitativeValue\",\"value\":\"Thương lượng\",\"unitText\":\"HOUR\"}}");
        assertNull(job.getMinSalary());
        assertNull(job.getMaxSalary());
        assertNull(job.getCurrency());
        assertNull(job.getSalaryPeriod());
    }

    @Test
    void salaryWithAmountKeepsCurrencyAndPeriod() {
        JobPosting job = extract("{\"@type\":\"MonetaryAmount\",\"currency\":\"USD\",\"value\":{\"@type\":"
                + "\"QuantitativeValue\",\"minValue\":1000,\"maxValue\":2000,\"unitText\":\"MONTH\"}}");
        assertEquals(1000L, job.getMinSalary());
        assertEquals(2000L, job.getMaxSalary());
        assertEquals("USD", job.getCurrency());
        assertEquals("MONTH", job.getSalaryPeriod());
    }
}
