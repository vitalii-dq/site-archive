package org.archive.sitearchive.archive;

import java.time.Instant;
import java.util.UUID;

/**
 * Unsaved jobs and pages in any status, for tests in any package. They are built with the package-private state
 * changes the worker makes through {@link ArchiveJobLifecycle}.
 */
public final class ArchiveJobs {

    public static final String SITEMAP_URL = "https://example.org/sitemap.xml";
    public static final int MAX_PAGES = 10;
    /** When the attempts of {@link #page} and {@link #pending} pages started. */
    public static final Instant ATTEMPTED_AT = Instant.parse("2026-01-01T00:00:00Z");
    /** When {@link #page} pages that succeeded were captured; later than {@link #ATTEMPTED_AT}. */
    public static final Instant CAPTURED_AT = ATTEMPTED_AT.plusSeconds(5);

    private ArchiveJobs() {
    }

    /** A job that reached the status at {@code createdAt}; a finished one with an archive has {@link #archiveFile}. */
    public static ArchiveJob job(Instant createdAt, ArchiveJobStatus status) {
        ArchiveJob job = ArchiveJob.queue(SITEMAP_URL, MAX_PAGES, createdAt);
        switch (status) {
            case QUEUED -> {
            }
            case RUNNING -> job.start(createdAt);
            case COMPLETED, COMPLETED_WITH_ERRORS -> {
                job.start(createdAt);
                job.complete(status == ArchiveJobStatus.COMPLETED_WITH_ERRORS, archiveFile(job.getId()), 1024, null,
                        createdAt);
            }
            case FAILED -> {
                job.start(createdAt);
                job.fail("failed", createdAt);
            }
        }
        return job;
    }

    /**
     * A job with every field set: its sitemap, at https://example.org, listed 12 URLs of which 1 was excluded, and it
     * completed with errors, with an archive of 48213 bytes and the summary "1 of 11 pages could not be captured.".
     */
    public static ArchiveJob completedWithErrors(Instant createdAt, Instant startedAt, Instant finishedAt) {
        ArchiveJob job = ArchiveJob.queue(SITEMAP_URL, MAX_PAGES, createdAt);
        job.start(startedAt);
        job.recordSitemap("https://example.org", 12, 1, startedAt);
        job.complete(true, archiveFile(job.getId()), 48213, "1 of 11 pages could not be captured.", finishedAt);
        return job;
    }

    /** Where ArchiveStorage keeps the job's ZIP, relative to the storage root. */
    public static String archiveFile(UUID jobId) {
        return jobId + "/archive.zip";
    }

    /**
     * A page of https://example.org after one attempt, unless it is pending. A captured page has a final URL that
     * differs from its source URL, to tell them apart.
     */
    public static ArchivePage page(UUID jobId, String path, PageStatus status) {
        ArchivePage page = ArchivePage.pending(jobId, "https://example.org" + path, Instant.EPOCH);
        switch (status) {
            case PENDING -> {
            }
            case SUCCEEDED -> {
                page.recordAttemptStarted(ATTEMPTED_AT);
                page.markSucceeded(200, "https://example.org" + path + "/final", "text/html; charset=utf-8",
                        "pages" + path + ".html", 123, CAPTURED_AT);
            }
            case FAILED -> {
                page.recordAttemptStarted(ATTEMPTED_AT);
                page.markFailed(404, "HTTP status 404");
            }
        }
        return page;
    }

    /** A pending page whose earlier attempts were all interrupted. */
    public static ArchivePage pending(UUID jobId, String path, int attempts) {
        ArchivePage page = page(jobId, path, PageStatus.PENDING);
        for (int i = 0; i < attempts; i++) {
            page.recordAttemptStarted(ATTEMPTED_AT);
        }
        return page;
    }
}
