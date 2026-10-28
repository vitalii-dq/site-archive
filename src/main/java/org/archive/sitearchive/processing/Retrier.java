package org.archive.sitearchive.processing;

import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.RetryableFailure;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Runs an action up to a number of attempts, waiting between them with the configured exponential backoff. Only
 * failures that report themselves as retryable are retried; any other exception ends the attempts at once.
 */
@Component
class Retrier {

    private final int maxAttempts;
    private final ExponentialBackOff backOff;

    Retrier(ArchiveProperties properties) {
        ArchiveProperties.Retry settings = properties.retry();
        this.maxAttempts = settings.maxAttempts();
        this.backOff = new ExponentialBackOff(settings.initialBackoff().toMillis(), settings.multiplier());
        this.backOff.setMaxInterval(settings.maxBackoff().toMillis());
    }

    int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @param attempts attempts allowed for this call, at least 1
     * @throws E                    the last failure, if it was not retryable or no attempts were left
     * @throws InterruptedException if the thread was interrupted during an attempt or a backoff
     */
    <T, E extends Exception> T run(int attempts, Attempt<T, E> attempt) throws E, InterruptedException {
        BackOffExecution backOffExecution = backOff.start();
        for (int number = 1; ; number++) {
            try {
                return attempt.run();
            } catch (Exception e) {
                if (number >= attempts || !(e instanceof RetryableFailure failure && failure.isRetryable())) {
                    throw e; // still typed as E, InterruptedException or a runtime exception
                }
                Thread.sleep(backOffExecution.nextBackOff());
            }
        }
    }

    <E extends Exception> void run(int attempts, VoidAttempt<E> attempt) throws E, InterruptedException {
        run(attempts, () -> {
            attempt.run();
            return null;
        });
    }

    @FunctionalInterface
    interface Attempt<T, E extends Exception> {
        T run() throws E, InterruptedException;
    }

    @FunctionalInterface
    interface VoidAttempt<E extends Exception> {
        void run() throws E, InterruptedException;
    }
}
