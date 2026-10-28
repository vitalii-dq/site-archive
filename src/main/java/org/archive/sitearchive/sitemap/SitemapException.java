package org.archive.sitearchive.sitemap;

import org.archive.sitearchive.fetch.RetryableFailure;

public class SitemapException extends Exception implements RetryableFailure {

    private final boolean retryable;

    public SitemapException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    @Override
    public boolean isRetryable() {
        return retryable;
    }
}
