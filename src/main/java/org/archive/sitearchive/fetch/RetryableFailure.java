package org.archive.sitearchive.fetch;

/** A failure that knows whether trying again might succeed. */
public interface RetryableFailure {

    boolean isRetryable();
}
