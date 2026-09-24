package org.example.jobpostingcrawler.address;

import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedNewAddressResolverTest {

    private NewLocationConverter converter;
    private MemoryGeoCache cache;
    private AtomicInteger calls;
    private CachedNewAddressResolver resolver;

    @BeforeEach
    void setUp() {
        converter = new NewLocationConverter();
        cache = new MemoryGeoCache();
        calls = new AtomicInteger();
        AddressGeocoder geocoder = query -> {
            calls.incrementAndGet();
            if (query.contains("Duy Tan")) {
                return new AddressGeocoder.GeoHit(List.of("Phường Cầu Giấy"), "Thành phố Hà Nội", true);
            }
            if (query.contains("Nguyen Van Troi")) {
                return new AddressGeocoder.GeoHit(List.of("Phường Bình Thạnh"), "Thành phố Hồ Chí Minh", true);
            }
            return AddressGeocoder.GeoHit.none();
        };
        resolver = new CachedNewAddressResolver(
                converter,
                geocoder,
                cache,
                properties(true),
                new GeoRateLimiter(Duration.ZERO)
        );
    }

    @Test
    void keepsOfflineMatchWithoutCallingTheMap() {
        String address = resolver.resolve("SSI Tower, 1 Châu Văn Liêm, Tu Liem, Ha Noi");

        assertEquals("SSI Tower, 1 Châu Văn Liêm, Phường Từ Liêm, Thành phố Hà Nội", address);
        assertEquals(0, calls.get());
    }

    @Test
    void geocodesASplitDistrictAndCachesTheWard() {
        String raw = "Epic tower, Lane 19 Duy Tan street, Cau Giay, Ha Noi";

        String first = resolver.resolve(raw);
        String second = resolver.resolve(raw);

        assertEquals("Epic tower, Lane 19 Duy Tan street, Phường Cầu Giấy, Thành phố Hà Nội", first);
        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsAWardOutsideTheOldDistrict() {
        String address = resolver.resolve("106 Nguyen Van Troi, Phu Nhuan, Ho Chi Minh");

        assertNull(address);
        assertEquals(1, calls.get());
        assertNull(resolver.resolve("106 Nguyen Van Troi, Phu Nhuan, Ho Chi Minh"));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotGeocodeWhenThereIsNoStreet() {
        assertNull(resolver.resolve("City, Ha Noi"));
        assertEquals(0, calls.get());
    }

    @Test
    void splitDistrictHintListsMoreThanOneWard() {
        NewLocationConverter.Resolution resolution = converter.resolve(
                "Epic tower, Lane 19 Duy Tan street, Cau Giay, Ha Noi");

        assertNull(resolution.newAddress());
        assertTrue(resolution.hint().allowed().size() > 1);
        assertTrue(resolution.hint().query().contains("Duy Tan"));
        assertTrue(resolution.hint().query().contains("Cau Giay"));
    }

    private static CrawlerProperties properties(boolean enabled) {
        return new CrawlerProperties(
                new CrawlerProperties.SchedulerProperties(false, Duration.ofMinutes(30)),
                new CrawlerProperties.QueueProperties(Duration.ofDays(7)),
                new CrawlerProperties.WorkerProperties(false, 1, Duration.ofSeconds(1), 1),
                new CrawlerProperties.RabbitProperties("ex", "q", "rk"),
                List.of(),
                new CrawlerProperties.GeocodeProperties(
                        enabled,
                        "https://nominatim.openstreetmap.org/search",
                        "job-posting-crawler-test",
                        Duration.ZERO,
                        Duration.ofSeconds(5),
                        Duration.ofDays(180),
                        Duration.ofDays(7)
                )
        );
    }

    private static final class MemoryGeoCache implements GeoCache {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            values.put(key, value);
        }
    }
}
