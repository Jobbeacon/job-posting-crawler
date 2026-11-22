package org.example.jobpostingcrawler.structurediscovery;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import org.example.jobpostingcrawler.structurediscovery.llm.GeminiKeyRotator;
import org.example.jobpostingcrawler.structurediscovery.llm.InvalidLlmResponseException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiKeyRotatorTest {

    private static final ApiException QUOTA = new ApiException(429, "RESOURCE_EXHAUSTED", "quota");
    private static final ApiException OVERLOADED = new ApiException(503, "UNAVAILABLE", "high demand");
    private static final ApiException BAD_KEY = new ApiException(403, "PERMISSION_DENIED", "key disabled");
    private static final ApiException BAD_REQUEST = new ApiException(400, "INVALID_ARGUMENT", "bad schema");

    private final List<Long> sleeps = new ArrayList<>();
    private final List<Integer> calls = new ArrayList<>();

    private GeminiKeyRotator rotator(int keys) {
        return new GeminiKeyRotator(keys, 2, Duration.ofSeconds(2), 3, Duration.ofSeconds(15), sleeps::add);
    }

    @Test
    void quotaErrorSwitchesToTheNextKeyAndLaterCallsStartThere() {
        GeminiKeyRotator rotator = rotator(3);

        String result = rotator.execute("op", key -> {
            calls.add(key);
            if (key == 0) {
                throw QUOTA;
            }
            return "ok#" + key;
        });

        assertEquals("ok#1", result);
        assertEquals(List.of(0, 1), calls, "no retry on the exhausted key");
        assertEquals(List.of(), sleeps);
        assertEquals(1, rotator.currentKey());

        calls.clear();
        rotator.execute("op", key -> {
            calls.add(key);
            return "ok";
        });
        assertEquals(List.of(1), calls, "next call starts on the key that still has quota");
    }

    @Test
    void badKeySwitchesToTheNextKey() {
        String result = rotator(2).execute("op", key -> {
            calls.add(key);
            if (key == 0) {
                throw BAD_KEY;
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(List.of(0, 1), calls);
    }

    @Test
    void invalidKeyAnsweredWith400SwitchesToTheNextKey() {
        // real Gemini answer to a wrong key
        ApiException invalidKey = new ApiException(400, "INVALID_ARGUMENT",
                "API key not valid. Please pass a valid API key.");
        String result = rotator(2).execute("op", key -> {
            calls.add(key);
            if (key == 0) {
                throw invalidKey;
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(List.of(0, 1), calls);
    }

    @Test
    void transientErrorsAreRetriedOnTheSameKeyWithBackoff() {
        int[] attempts = {0};
        String result = rotator(2).execute("op", key -> {
            calls.add(key);
            if (++attempts[0] <= 2) {
                throw attempts[0] == 1 ? OVERLOADED : new InvalidLlmResponseException("truncated JSON");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(List.of(0, 0, 0), calls);
        assertEquals(List.of(2000L, 4000L), sleeps);
    }

    @Test
    void keyIsSwitchedWhenTransientErrorsPersist() {
        String result = rotator(2).execute("op", key -> {
            calls.add(key);
            if (key == 0) {
                throw new GenAiIOException("timeout");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(List.of(0, 0, 0, 1), calls, "1 call + 2 retries on key 1, then key 2");
    }

    @Test
    void waitsAndRetriesWhenEveryKeyIsRateLimited() {
        // per-minute quota: every key answers 429 until the first wait is over
        String result = rotator(2).execute("op", key -> {
            calls.add(key);
            if (sleeps.isEmpty()) {
                throw QUOTA;
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(List.of(0, 1, 0), calls);
        assertEquals(List.of(15_000L), sleeps);
    }

    @Test
    void givesUpAfterTheLastRoundOfRateLimits() {
        ApiException thrown = assertThrows(ApiException.class, () -> rotator(2).execute("op", key -> {
            calls.add(key);
            throw QUOTA;
        }));
        assertSame(QUOTA, thrown);
        assertEquals(6, calls.size(), "3 rounds over 2 keys");
        assertEquals(List.of(15_000L, 30_000L), sleeps);
    }

    @Test
    void otherErrorsAreThrownAtOnce() {
        assertThrows(ApiException.class, () -> rotator(3).execute("op", key -> {
            calls.add(key);
            throw BAD_REQUEST;
        }));
        assertEquals(List.of(0), calls);
        assertEquals(List.of(), sleeps);
    }

    @Test
    void mixedFailuresDoNotWaitForTheQuota() {
        // one key over quota, the other overloaded: not a per-minute quota on every key, no 15s wait
        assertThrows(RuntimeException.class, () -> rotator(2).execute("op", key -> {
            calls.add(key);
            throw key == 0 ? QUOTA : OVERLOADED;
        }));
        assertEquals(List.of(2000L, 4000L), sleeps, "only the backoff of the overloaded key");
    }
}
