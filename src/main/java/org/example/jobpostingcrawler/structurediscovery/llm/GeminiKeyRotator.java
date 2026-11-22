package org.example.jobpostingcrawler.structurediscovery.llm;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Calls Gemini over several API keys, same rules as Jobbeacon-be (GeminiClientPool + GeminiRetryExecutor with
 * rotateKeyOnRateLimit):
 * <ul>
 *   <li>429 (quota): switch to the next key at once; the key in use is shared by all calls, so later calls
 *   start on a key that still has quota;</li>
 *   <li>bad, expired or disabled key (401/403, or 400 "API key not valid"): switch to the next key;</li>
 *   <li>5xx, 408, network errors, unparsable answer: retry on the same key with exponential backoff, then
 *   switch to the next key;</li>
 *   <li>other errors (400...): thrown at once.</li>
 * </ul>
 * When every key answered 429 (free tier quotas are per minute), waits 15s then 30s and tries all keys again.
 */
@Slf4j
public class GeminiKeyRotator {

    /** Pauses the calling thread; replaced in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;

    private final int keyCount;
    private final int maxRetries;
    private final Duration initialDelay;
    private final int rateLimitRounds;
    private final Duration rateLimitWait;
    private final Sleeper sleeper;
    private final AtomicInteger current = new AtomicInteger();

    public GeminiKeyRotator(int keyCount) {
        this(keyCount, 2, Duration.ofSeconds(2), 3, Duration.ofSeconds(15), Thread::sleep);
    }

    /**
     * @param maxRetries      retries on the same key for transient errors (5xx, timeouts, invalid answer)
     * @param initialDelay    first backoff, doubled after each retry
     * @param rateLimitRounds passes over all keys while every key answers 429
     * @param rateLimitWait   wait after the 1st pass (x2 after the 2nd...)
     */
    public GeminiKeyRotator(int keyCount, int maxRetries, Duration initialDelay, int rateLimitRounds,
                            Duration rateLimitWait, Sleeper sleeper) {
        if (keyCount <= 0) {
            throw new IllegalArgumentException("At least one Gemini API key is required");
        }
        this.keyCount = keyCount;
        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.rateLimitRounds = Math.max(1, rateLimitRounds);
        this.rateLimitWait = rateLimitWait;
        this.sleeper = sleeper;
    }

    /** Key the next call starts with (0-based). */
    public int currentKey() {
        return current.get();
    }

    /**
     * @param call performs the request with the key of the given index
     */
    public <T> T execute(String operation, IntFunction<T> call) {
        RuntimeException last = null;
        for (int round = 1; round <= rateLimitRounds; round++) {
            boolean allRateLimited = true;
            int start = current.get();
            for (int tried = 0; tried < keyCount; tried++) {
                int key = (start + tried) % keyCount;
                try {
                    return executeWithRetry(operation, key, call);
                } catch (RuntimeException e) {
                    if (!switchesKey(e)) {
                        throw e;
                    }
                    last = e;
                    allRateLimited &= isRateLimited(e);
                    rotateFrom(key);
                }
            }
            if (!allRateLimited || round == rateLimitRounds) {
                break;
            }
            long waitMs = rateLimitWait.toMillis() * round;
            log.warn("[StructureDiscovery] {}: all {} Gemini key(s) rate limited, waiting {}s", operation, keyCount,
                    waitMs / 1000);
            sleep(waitMs, last);
        }
        if (keyCount > 1) {
            log.error("[StructureDiscovery] {} failed on all {} Gemini keys", operation, keyCount);
        }
        throw last;
    }

    private <T> T executeWithRetry(String operation, int key, IntFunction<T> call) {
        long delayMs = initialDelay.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return call.apply(key);
            } catch (RuntimeException e) {
                String error = describe(e);
                if (isRateLimited(e) || isAuthError(e)) {
                    log.warn("[StructureDiscovery] {} key#{}: {}, switching key", operation, key + 1, error);
                    throw e;
                }
                if (!isTransient(e)) {
                    log.error("[StructureDiscovery] {} key#{} failed: {}", operation, key + 1, error);
                    throw e;
                }
                if (attempt > maxRetries) {
                    log.warn("[StructureDiscovery] {} key#{} failed after {} attempts: {}", operation, key + 1,
                            attempt, error);
                    throw e;
                }
                log.warn("[StructureDiscovery] {} key#{} failed (attempt {}/{}): {}. Retry in {} ms", operation,
                        key + 1, attempt, maxRetries + 1, error, delayMs);
                sleep(delayMs, e);
                delayMs *= 2;
            }
        }
    }

    private void rotateFrom(int failedKey) {
        if (keyCount <= 1) {
            return;
        }
        int next = (failedKey + 1) % keyCount;
        if (current.compareAndSet(failedKey, next)) {
            log.warn("[StructureDiscovery] rotate Gemini API key #{} -> #{}", failedKey + 1, next + 1);
        }
    }

    private boolean switchesKey(RuntimeException e) {
        return isRateLimited(e) || isAuthError(e) || isTransient(e);
    }

    static boolean isRateLimited(RuntimeException e) {
        return e instanceof ApiException api && api.code() == TOO_MANY_REQUESTS;
    }

    /**
     * Bad, expired or disabled key. Gemini answers an invalid key with 400 INVALID_ARGUMENT "API key not valid"
     * (reason API_KEY_INVALID), not 401.
     */
    static boolean isAuthError(RuntimeException e) {
        if (!(e instanceof ApiException api)) {
            return false;
        }
        if (api.code() == UNAUTHORIZED || api.code() == FORBIDDEN) {
            return true;
        }
        String message = api.message() == null ? "" : api.message();
        return api.code() == BAD_REQUEST && (message.contains("API key") || message.contains("API_KEY_INVALID"));
    }

    /** Worth retrying: overload/timeout, network, or an answer that is not the expected JSON. */
    static boolean isTransient(RuntimeException e) {
        if (e instanceof ApiException api) {
            return api.code() == REQUEST_TIMEOUT || api.code() >= SERVER_ERROR;
        }
        return e instanceof GenAiIOException || e instanceof InvalidLlmResponseException;
    }

    private static String describe(RuntimeException e) {
        if (e instanceof ApiException api) {
            return "code=" + api.code() + " status=" + api.status() + " msg=" + api.message();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private void sleep(long ms, RuntimeException cause) {
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
