package org.archive.sitearchive.archive;

import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.sitemap.SitemapUrls;
import org.archive.sitearchive.storage.StoredArchive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The state changes a background worker makes to archive jobs and pages. This is the only way code outside
 * this package can modify them. Each method is one short transaction; network calls, backoff and file writes
 * all happen between these calls, never inside them.
 */
@Service
public class ArchiveJobLifecycle {

    static final String CRASH_LIMIT_SUMMARY = "The archive job was abandoned after a crash of the service or a fatal "
            + "error stopped it %d times. The job itself may be the cause, for example by needing too much memory.";

    private final ArchiveJobRepository jobs;
    private final ArchivePageRepository pages;
    private final Clock clock;

    ArchiveJobLifecycle(ArchiveJobRepository jobs, ArchivePageRepository pages, Clock clock) {
        this.jobs = jobs;
        this.pages = pages;
        this.clock = clock;
    }

    @Transactional
    public Optional<UUID> claimNextQueuedJob() {
        return jobs.lockOldestQueued().map(job -> {
            job.start(clock.instant());
            return job.getId();
        });
    }

    @Transactional(readOnly = true)
    public JobSnapshot loadJob(UUID jobId) {
        return JobSnapshot.of(getJob(jobId));
    }

    @Transactional
    public void recordSitemap(UUID jobId, SitemapUrls urls) {
        Instant now = clock.instant();
        getJob(jobId).recordSitemap(urls.origin().toString(), urls.listedUrlCount(), urls.excludedUrlCount(), now);
        pages.saveAll(urls.pageUrls().stream()
                .map(url -> ArchivePage.pending(jobId, url.toString(), now))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<PendingPage> pendingPages(UUID jobId) {
        return pages.findByArchiveJobIdAndStatusOrderById(jobId, PageStatus.PENDING, PendingPage.class);
    }

    @Transactional
    public void recordAttemptStarted(long pageId) {
        ArchivePage page = getPage(pageId);
        page.recordAttemptStarted(clock.instant());
    }

    @Transactional
    public void markPageSucceeded(long pageId, FetchedResponse<?> response, String fileName, long sizeBytes) {
        getPage(pageId).markSucceeded(response.statusCode(), response.finalUrl().toString(), response.contentType(),
                fileName, sizeBytes, clock.instant());
    }

    @Transactional
    public void markPageFailed(long pageId, Integer httpStatus, String error) {
        getPage(pageId).markFailed(httpStatus, error);
    }

    @Transactional(readOnly = true)
    public List<PageResult> pageResults(UUID jobId) {
        return pages.findByArchiveJobIdOrderById(jobId, PageResult.class);
    }

    @Transactional
    public void completeJob(UUID jobId, boolean withErrors, StoredArchive archive, String errorSummary) {
        getJob(jobId).complete(withErrors, archive.relativePath(), archive.sizeBytes(), errorSummary, clock.instant());
    }

    @Transactional
    public void failJob(UUID jobId, String errorSummary) {
        getJob(jobId).fail(errorSummary, clock.instant());
    }

    /**
     * Returns the job of a worker that was stopped for a shutdown to the queue, so that the next start does not
     * count it as a crash. Does nothing if the job finished in the meantime.
     */
    @Transactional
    public void requeueStoppedJob(UUID jobId) {
        ArchiveJob job = getJob(jobId);
        if (job.getStatus() == ArchiveJobStatus.RUNNING) {
            job.requeueAfterInterruption(clock.instant());
        }
    }

    /**
     * Recovers the jobs left RUNNING by a previous process. Workers hand their job back when they are stopped, so
     * such a job was running when the process crashed or was killed, for example by
     * {@code -XX:+ExitOnOutOfMemoryError}. Each one counts a crash, see {@link #recordCrash(UUID, int)}. Assumes a
     * single application instance.
     */
    @Transactional
    public RecoveredJobs recoverCrashedJobs(int maxCrashes) {
        Instant now = clock.instant();
        List<UUID> requeued = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        for (ArchiveJob job : jobs.findByStatus(ArchiveJobStatus.RUNNING)) {
            (recordCrash(job, maxCrashes, now) ? requeued : failed).add(job.getId());
        }
        return new RecoveredJobs(requeued, failed);
    }

    /**
     * Counts a crash of a running job: the service crashed, or a fatal error stopped the job. The job is requeued,
     * unless this was its {@code maxCrashes}th crash: the job itself may be the cause, so it is failed instead of
     * being retried forever.
     *
     * @return whether the job was requeued
     */
    @Transactional
    public boolean recordCrash(UUID jobId, int maxCrashes) {
        return recordCrash(getJob(jobId), maxCrashes, clock.instant());
    }

    private static boolean recordCrash(ArchiveJob job, int maxCrashes, Instant now) {
        job.recordCrash(now);
        if (job.getCrashCount() >= maxCrashes) {
            job.fail(CRASH_LIMIT_SUMMARY.formatted(job.getCrashCount()), now);
            return false;
        }
        job.requeueAfterInterruption(now);
        return true;
    }

    private ArchiveJob getJob(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new IllegalStateException("Archive job not found: " + jobId));
    }

    private ArchivePage getPage(long pageId) {
        return pages.findById(pageId).orElseThrow(() -> new IllegalStateException("Archive page not found: " + pageId));
    }
}
