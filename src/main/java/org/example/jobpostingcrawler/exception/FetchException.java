package org.example.jobpostingcrawler.exception;

import lombok.Getter;

@Getter
public class FetchException extends RuntimeException {

    private final String url;
    private final int statusCode;

    public FetchException(String url, int statusCode) {
        super("Fetch failed for url=" + url + " with status=" + statusCode);
        this.url = url;
        this.statusCode = statusCode;
    }
}
