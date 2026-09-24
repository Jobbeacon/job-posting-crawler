package org.example.jobpostingcrawler.address;

import java.time.Duration;

public interface GeoCache {

    String get(String key);

    void put(String key, String value, Duration ttl);
}
