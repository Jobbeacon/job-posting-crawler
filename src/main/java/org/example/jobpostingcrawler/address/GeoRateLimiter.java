package org.example.jobpostingcrawler.address;

import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class GeoRateLimiter {

    private final long intervalNanos;
    private final Object lock = new Object();
    private long nextAt;

    @Autowired
    public GeoRateLimiter(CrawlerProperties properties) {
        this(properties.geocode() == null ? Duration.ofSeconds(1) : properties.geocode().minInterval());
    }

    GeoRateLimiter(Duration interval) {
        this.intervalNanos = Math.max(0, interval.toNanos());
    }

    public void awaitTurn() {
        if (intervalNanos == 0) {
            return;
        }
        long waitNanos;
        synchronized (lock) {
            long now = System.nanoTime();
            waitNanos = Math.max(0, nextAt - now);
            nextAt = Math.max(now, nextAt) + intervalNanos;
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
