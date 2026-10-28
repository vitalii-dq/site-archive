package org.archive.sitearchive.fetch;

/** A failed download, classified as worth retrying or not. */
public class FetchException extends Exception implements RetryableFailure {

    private final boolean retryable;
    private final Integer httpStatus;

    private FetchException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public static FetchException retryable(String message) {
        return new FetchException(message, true, null, null);
    }

    public static FetchException retryable(String message, Throwable cause) {
        return new FetchException(message, true, null, cause);
    }

    public static FetchException permanent(String message) {
        return new FetchException(message, false, null, null);
    }

    public static FetchException permanent(String message, int httpStatus) {
        return new FetchException(message, false, httpStatus, null);
    }

    /** Rate limiting and server errors are usually temporary; other error statuses are not. */
    public static FetchException forHttpStatus(int httpStatus) {
        boolean transientStatus = httpStatus == 429 || httpStatus >= 500;
        return new FetchException("HTTP status " + httpStatus, transientStatus, httpStatus, null);
    }

    @Override
    public boolean isRetryable() {
        return retryable;
    }

    /** The HTTP status that caused the failure, or {@code null} if no response was received. */
    public Integer httpStatus() {
        return httpStatus;
    }
}
