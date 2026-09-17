package org.example.jobpostingcrawler.utils;

import java.net.URI;
import java.util.Locale;

public class UrlUtils {
    public static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            String domain = new URI(url).getHost();
            if (domain == null) {
                return null;
            }

            domain = domain.toLowerCase(Locale.ROOT);
            return domain.startsWith("www.") ? domain.substring(4) : domain;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String stripWww(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceFirst("^(https?://)?www\\.", "$1");
    }
}
