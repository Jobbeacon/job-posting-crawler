package org.example.jobpostingcrawler.fetcher;

public record ProxyConfig(String host, String port, String username, String password) {

    static ProxyConfig parse(String raw) {
        String[] parts = raw.split(":", 4);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid proxy format, expected host:port[:username:password], got: " + raw);
        }
        String username = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
        String password = parts.length > 3 && !parts[3].isBlank() ? parts[3] : null;
        return new ProxyConfig(parts[0], parts[1], username, password);
    }

    String server() {
        return "http://" + host + ":" + port;
    }

    boolean hasCredentials() {
        return username != null && password != null;
    }
}
