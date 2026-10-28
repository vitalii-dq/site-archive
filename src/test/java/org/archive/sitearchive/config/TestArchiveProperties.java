package org.archive.sitearchive.config;

import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Builds {@link ArchiveProperties} for tests that create components by hand, so that each test sets only the values
 * it depends on. Everything else has the default from application.yml, except that backoffs and the poll interval
 * are shortened so that tests do not wait. The storage root has no default: a test that writes files must pass a
 * temporary directory.
 */
public final class TestArchiveProperties {

    private Path storageRoot;
    private int defaultMaxPages = 100;
    private int maxPagesLimit = 1000;
    private DataSize maxSitemapSize = DataSize.ofMegabytes(10);
    private DataSize maxPageSize = DataSize.ofMegabytes(5);
    private DataSize maxAssetSize = DataSize.ofMegabytes(25);
    private int maxAssetsPerJob = 2000;
    private int maxRedirects = 5;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(20);
    private String userAgent = "SiteArchiveBot/1.0";
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(1);
    private double multiplier = 2.0;
    private Duration maxBackoff = Duration.ofMillis(1);
    private int concurrency = 2;
    private Duration pollInterval = Duration.ofMillis(10);
    private int maxCrashesPerJob = 3;

    private TestArchiveProperties() {
    }

    public static TestArchiveProperties builder() {
        return new TestArchiveProperties();
    }

    /** The defaults, without a storage root. */
    public static ArchiveProperties defaults() {
        return builder().build();
    }

    public TestArchiveProperties storageRoot(Path storageRoot) {
        this.storageRoot = storageRoot;
        return this;
    }

    public TestArchiveProperties defaultMaxPages(int defaultMaxPages) {
        this.defaultMaxPages = defaultMaxPages;
        return this;
    }

    public TestArchiveProperties maxPagesLimit(int maxPagesLimit) {
        this.maxPagesLimit = maxPagesLimit;
        return this;
    }

    public TestArchiveProperties maxPageSize(DataSize maxPageSize) {
        this.maxPageSize = maxPageSize;
        return this;
    }

    public TestArchiveProperties maxAssetsPerJob(int maxAssetsPerJob) {
        this.maxAssetsPerJob = maxAssetsPerJob;
        return this;
    }

    public TestArchiveProperties maxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
        return this;
    }

    public TestArchiveProperties requestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }

    public TestArchiveProperties userAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public TestArchiveProperties maxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }

    public TestArchiveProperties concurrency(int concurrency) {
        this.concurrency = concurrency;
        return this;
    }

    public TestArchiveProperties maxCrashesPerJob(int maxCrashesPerJob) {
        this.maxCrashesPerJob = maxCrashesPerJob;
        return this;
    }

    public ArchiveProperties build() {
        return new ArchiveProperties(
                new ArchiveProperties.Storage(storageRoot),
                new ArchiveProperties.Limits(defaultMaxPages, maxPagesLimit, maxSitemapSize, maxPageSize, maxAssetSize,
                        maxAssetsPerJob, maxRedirects, connectTimeout, requestTimeout),
                new ArchiveProperties.Fetch(userAgent),
                new ArchiveProperties.Retry(maxAttempts, initialBackoff, multiplier, maxBackoff),
                new ArchiveProperties.Worker(concurrency, pollInterval, maxCrashesPerJob));
    }
}
