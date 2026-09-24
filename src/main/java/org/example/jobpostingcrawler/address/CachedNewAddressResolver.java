package org.example.jobpostingcrawler.address;

import org.example.jobpostingcrawler.config.CrawlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CachedNewAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(CachedNewAddressResolver.class);
    private static final String KEY_PREFIX = "crawler:address:geo:";
    private static final String MISS = "0";
    private static final char SEP = '\u001f';

    private final NewLocationConverter converter;
    private final AddressGeocoder geocoder;
    private final GeoCache cache;
    private final CrawlerProperties properties;
    private final GeoRateLimiter rateLimiter;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public CachedNewAddressResolver(
            NewLocationConverter converter,
            AddressGeocoder geocoder,
            GeoCache cache,
            CrawlerProperties properties,
            GeoRateLimiter rateLimiter
    ) {
        this.converter = converter;
        this.geocoder = geocoder;
        this.cache = cache;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    public String resolve(String rawLocation) {
        NewLocationConverter.Resolution resolution = converter.resolve(rawLocation);
        if (resolution.newAddress() != null) {
            return resolution.newAddress();
        }
        NewLocationConverter.GeocodeHint hint = resolution.hint();
        CrawlerProperties.GeocodeProperties geocode = properties.geocode();
        if (hint == null || geocode == null || !geocode.enabled()) {
            return null;
        }
        String key = KEY_PREFIX + sha256(NewLocationConverter.fold(hint.query()));
        String cached = cache.get(key);
        if (cached != null) {
            return fromCache(cached, hint);
        }
        CompletableFuture<String> created = new CompletableFuture<>();
        CompletableFuture<String> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            String resolved = lookup(hint, key, geocode);
            created.complete(resolved);
            return resolved;
        } catch (RuntimeException exception) {
            created.complete(null);
            throw exception;
        } finally {
            inFlight.remove(key);
        }
    }

    private String lookup(NewLocationConverter.GeocodeHint hint, String key, CrawlerProperties.GeocodeProperties geocode) {
        rateLimiter.awaitTurn();
        AddressGeocoder.GeoHit hit = geocoder.lookup(hint.query());
        if (!hit.definitive()) {
            return null;
        }
        if (!provinceAcceptable(hit.provinceText(), hint)) {
            cache.put(key, MISS, geocode.negativeTtl());
            return null;
        }
        NewLocationConverter.AllowedWard ward = matchWard(hit, hint);
        if (ward == null) {
            cache.put(key, MISS, geocode.negativeTtl());
            return null;
        }
        String address = hint.street() + ", " + ward.name() + ", " + hint.provinceName();
        cache.put(key, "1" + SEP + ward.name() + SEP + hint.provinceName(), geocode.positiveTtl());
        log.info("Cached geocoded ward for {}", hint.provinceName());
        return address;
    }

    private static String fromCache(String cached, NewLocationConverter.GeocodeHint hint) {
        if (MISS.equals(cached)) {
            return null;
        }
        String[] parts = cached.split(String.valueOf(SEP), 3);
        if (parts.length != 3 || !"1".equals(parts[0]) || parts[1].isBlank()) {
            return null;
        }
        return hint.street() + ", " + parts[1] + ", " + hint.provinceName();
    }

    private static boolean provinceAcceptable(String provinceText, NewLocationConverter.GeocodeHint hint) {
        if (provinceText == null || provinceText.isBlank()) {
            return true;
        }
        String folded = NewLocationConverter.fold(provinceText);
        String expected = NewLocationConverter.fold(hint.provinceName());
        return folded.equals(expected) || expected.endsWith(folded) || folded.endsWith(expected);
    }

    private static NewLocationConverter.AllowedWard matchWard(
            AddressGeocoder.GeoHit hit,
            NewLocationConverter.GeocodeHint hint
    ) {
        NewLocationConverter.AllowedWard found = null;
        for (String place : hit.places()) {
            for (NewLocationConverter.AllowedWard ward : hint.allowed()) {
                if (!nameMatches(place, ward)) {
                    continue;
                }
                if (found != null && !found.code().equals(ward.code())) {
                    return null;
                }
                found = ward;
            }
        }
        return found;
    }

    private static boolean nameMatches(String place, NewLocationConverter.AllowedWard ward) {
        String folded = NewLocationConverter.fold(place);
        if (folded.equals(ward.nameFold()) || folded.equals(ward.shortFold())) {
            return true;
        }
        for (String prefix : new String[]{"phuong ", "xa ", "thi tran "}) {
            if (folded.startsWith(prefix) && folded.substring(prefix.length()).equals(ward.shortFold())) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
