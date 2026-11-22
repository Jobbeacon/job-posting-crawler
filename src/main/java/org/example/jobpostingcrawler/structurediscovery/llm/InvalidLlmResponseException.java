package org.example.jobpostingcrawler.structurediscovery.llm;

/** The LLM answered, but not with the expected JSON (empty, truncated...): worth retrying. */
public class InvalidLlmResponseException extends IllegalStateException {

    public InvalidLlmResponseException(String message) {
        super(message);
    }

    public InvalidLlmResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
