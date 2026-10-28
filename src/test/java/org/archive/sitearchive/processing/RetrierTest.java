package org.archive.sitearchive.processing;

import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrierTest {

    private final Retrier retrier = new Retrier(new ArchiveProperties(null, null, null,
            new ArchiveProperties.Retry(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5)), null));
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void retriesRetryableFailuresUntilAnAttemptSucceeds() throws Exception {
        String result = retrier.run(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw FetchException.retryable("timeout");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void retriesActionsWithoutAResult() throws Exception {
        retrier.run(3, () -> {
            if (calls.incrementAndGet() < 2) {
                throw FetchException.retryable("timeout");
            }
        });

        assertThat(calls).hasValue(2);
    }

    @Test
    void rethrowsTheLastFailureOnceTheAttemptsAreUsedUp() {
        FetchException last = FetchException.retryable("still down");

        assertThatThrownBy(() -> retrier.run(2, () -> {
            calls.incrementAndGet();
            throw last;
        })).isSameAs(last);
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryPermanentFailuresOrRuntimeExceptions() {
        assertThatThrownBy(() -> retrier.run(3, () -> {
            calls.incrementAndGet();
            throw FetchException.permanent("404");
        })).isInstanceOf(FetchException.class);
        assertThatThrownBy(() -> retrier.run(3, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void stopsWhenInterruptedDuringTheBackoff() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> retrier.run(3, () -> {
                calls.incrementAndGet();
                throw FetchException.retryable("timeout");
            })).isInstanceOf(InterruptedException.class);
            assertThat(calls).hasValue(1);
        } finally {
            Thread.interrupted(); // clear the flag for the next test
        }
    }
}
