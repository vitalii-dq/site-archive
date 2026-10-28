package org.archive.sitearchive.archive;

import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.sitemap.SitemapUrls;
import org.archive.sitearchive.storage.StoredArchive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
    public void markPageSucceeded(long pageId, FetchedResponse response, String fileName) {
        getPage(pageId).markSucceeded(response.statusCode(), response.finalUrl().toString(), response.contentType(),
                fileName, response.body().length, clock.instant());
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

    /** Returns jobs left RUNNING by a previous process to the queue. Assumes a single application instance. */
    @Transactional
    public List<UUID> requeueInterruptedJobs() {
        Instant now = clock.instant();
        List<ArchiveJob> interrupted = jobs.findByStatus(ArchiveJobStatus.RUNNING);
        interrupted.forEach(job -> job.requeueAfterInterruption(now));
        return interrupted.stream().map(ArchiveJob::getId).toList();
    }

    private ArchiveJob getJob(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new IllegalStateException("Archive job not found: " + jobId));
    }

    private ArchivePage getPage(long pageId) {
        return pages.findById(pageId).orElseThrow(() -> new IllegalStateException("Archive page not found: " + pageId));
    }
}
