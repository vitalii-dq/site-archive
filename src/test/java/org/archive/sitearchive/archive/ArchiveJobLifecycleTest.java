package org.archive.sitearchive.archive;

import jakarta.persistence.EntityManager;
import org.archive.sitearchive.PostgresTestConfiguration;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.WebOrigin;
import org.archive.sitearchive.sitemap.SitemapUrls;
import org.archive.sitearchive.storage.StoredArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.archive.sitearchive.archive.ArchiveJobStatus.COMPLETED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.COMPLETED_WITH_ERRORS;
import static org.archive.sitearchive.archive.ArchiveJobStatus.FAILED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.QUEUED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@Import(PostgresTestConfiguration.class)
class ArchiveJobLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");
    private static final Instant NOW = T0.plusSeconds(60);
    private static final int MAX_CRASHES = 3;

    @Autowired
    private ArchiveJobRepository jobs;

    @Autowired
    private ArchivePageRepository pages;

    @Autowired
    private EntityManager entityManager;

    private ArchiveJobLifecycle lifecycle;

    /**
     * Built directly rather than as a bean, so that this test shares the cached context of the repository tests. Its
     * methods' {@code @Transactional} would only join the test's transaction anyway.
     */
    @BeforeEach
    void createLifecycle() {
        lifecycle = new ArchiveJobLifecycle(jobs, pages, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("claimNextQueuedJob starts the oldest queued job and leaves the others queued")
    void claimsTheOldestQueuedJob() {
        UUID oldest = save(ArchiveJobs.job(T0, QUEUED));
        UUID newer = save(ArchiveJobs.job(T0.plusSeconds(1), QUEUED));

        assertThat(lifecycle.claimNextQueuedJob()).contains(oldest);

        ArchiveJob claimed = reload(oldest);
        assertThat(claimed.getStatus()).isEqualTo(RUNNING);
        assertThat(claimed.getStartedAt()).isEqualTo(NOW);
        assertThat(claimed.getUpdatedAt()).isEqualTo(NOW);
        assertThat(reload(newer).getStatus()).isEqualTo(QUEUED);
    }

    @Test
    @DisplayName("claimNextQueuedJob keeps the time a requeued job first started")
    void keepsTheFirstStartTime() {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));
        lifecycle.requeueStoppedJob(job);

        assertThat(lifecycle.claimNextQueuedJob()).contains(job);

        ArchiveJob restarted = reload(job);
        assertThat(restarted.getStatus()).isEqualTo(RUNNING);
        assertThat(restarted.getStartedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("recordSitemap stores the counts and origin, and creates the selected pages as pending, in sitemap order")
    void recordsTheSitemap() {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));
        assertThat(lifecycle.loadJob(job).sitemapProcessed()).isFalse();
        WebOrigin origin = WebOrigin.parse("https://example.org");

        lifecycle.recordSitemap(job, new SitemapUrls(origin,
                List.of(URI.create("https://example.org/b"), URI.create("https://example.org/a")), 5, 3));

        ArchiveJob recorded = reload(job);
        assertThat(recorded.getSiteOrigin()).isEqualTo("https://example.org");
        assertThat(recorded.getSitemapUrlCount()).isEqualTo(5);
        assertThat(recorded.getExcludedUrlCount()).isEqualTo(3);
        assertThat(lifecycle.loadJob(job)).isEqualTo(
                new JobSnapshot(job, URI.create(ArchiveJobs.SITEMAP_URL), ArchiveJobs.MAX_PAGES, origin));
        assertThat(lifecycle.pendingPages(job)).extracting(PendingPage::sourceUrl, PendingPage::attempts)
                .containsExactly(
                        tuple("https://example.org/b", 0),
                        tuple("https://example.org/a", 0));
    }

    @Test
    @DisplayName("Looking up an unknown job fails")
    void failsForAnUnknownJob() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> lifecycle.loadJob(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    @DisplayName("recordAttemptStarted counts the attempt and records its time")
    void countsAnAttempt() {
        long page = savePage(ArchiveJobs.pending(save(ArchiveJobs.job(T0, RUNNING)), "/a", 1));

        lifecycle.recordAttemptStarted(page);

        ArchivePage attempted = reloadPage(page);
        assertThat(attempted.getAttempts()).isEqualTo(2);
        assertThat(attempted.getLastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("markPageSucceeded stores the response and the file, cutting a long Content-Type to the column size")
    void marksAPageAsSucceeded() {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));
        long page = savePage(ArchiveJobs.pending(job, "/a", 1));
        String longContentType = "text/html; " + "x".repeat(300);

        lifecycle.markPageSucceeded(page, new FetchedResponse<>(URI.create("https://example.org/a/final"), 200,
                longContentType, null), "pages/1-a.html", 2048);

        assertThat(lifecycle.pageResults(job)).containsExactly(new PageResult("https://example.org/a",
                PageStatus.SUCCEEDED, "pages/1-a.html", 200, longContentType.substring(0, 255),
                "https://example.org/a/final", 1, null));
        ArchivePage captured = reloadPage(page);
        assertThat(captured.getSizeBytes()).isEqualTo(2048);
        assertThat(captured.getCapturedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("markPageFailed stores the HTTP status and the error, cutting a long error to 1000 characters")
    void marksAPageAsFailed() {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));
        long page = savePage(ArchiveJobs.pending(job, "/a", 1));
        String longError = "e".repeat(1500);

        lifecycle.markPageFailed(page, 503, longError);

        assertThat(lifecycle.pageResults(job)).containsExactly(new PageResult("https://example.org/a",
                PageStatus.FAILED, null, 503, null, null, 1, longError.substring(0, 1000)));
    }

    @Test
    @DisplayName("A page that is no longer pending cannot change")
    void refusesToChangeAFinishedPage() {
        long page = savePage(ArchiveJobs.page(save(ArchiveJobs.job(T0, RUNNING)), "/a", PageStatus.SUCCEEDED));

        assertThatThrownBy(() -> lifecycle.markPageFailed(page, 500, "late failure"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("completeJob finishes the job with its archive, with errors if pages failed")
    void completesAJob(boolean withErrors) {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));

        lifecycle.completeJob(job, withErrors, new StoredArchive(job + "/archive.zip", 4096),
                withErrors ? "1 of 2 pages could not be captured." : null);

        ArchiveJob completed = reload(job);
        assertThat(completed.getStatus()).isEqualTo(withErrors ? COMPLETED_WITH_ERRORS : COMPLETED);
        assertThat(completed.getArchiveFile()).isEqualTo(job + "/archive.zip");
        assertThat(completed.getArchiveSizeBytes()).isEqualTo(4096);
        assertThat(completed.getErrorSummary()).isEqualTo(withErrors ? "1 of 2 pages could not be captured." : null);
        assertThat(completed.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("failJob finishes the job with the error summary")
    void failsAJob() {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));

        lifecycle.failJob(job, "The sitemap could not be processed.");

        ArchiveJob failed = reload(job);
        assertThat(failed.getStatus()).isEqualTo(FAILED);
        assertThat(failed.getErrorSummary()).isEqualTo("The sitemap could not be processed.");
        assertThat(failed.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("A job cannot make a change its status does not allow, such as completing before it ran")
    void refusesAChangeTheStatusDoesNotAllow() {
        UUID job = save(ArchiveJobs.job(T0, QUEUED));

        assertThatThrownBy(() -> lifecycle.completeJob(job, false, new StoredArchive(job + "/archive.zip", 1), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot move from QUEUED to COMPLETED");
    }

    @Test
    @DisplayName("requeueStoppedJob returns a running job to the queue without counting a crash")
    void requeuesAStoppedJob() {
        UUID job = save(ArchiveJobs.job(T0, RUNNING));

        lifecycle.requeueStoppedJob(job);

        ArchiveJob requeued = reload(job);
        assertThat(requeued.getStatus()).isEqualTo(QUEUED);
        assertThat(requeued.getCrashCount()).isZero();
    }

    @Test
    @DisplayName("requeueStoppedJob leaves a job that finished in the meantime as it is")
    void leavesAFinishedJob() {
        UUID job = save(ArchiveJobs.job(T0, FAILED));

        lifecycle.requeueStoppedJob(job);

        assertThat(reload(job).getStatus()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("recoverCrashedJobs counts a crash of each running job: requeued, or failed at the limit")
    void recoversCrashedJobs() {
        UUID firstCrash = save(ArchiveJobs.job(T0, RUNNING));
        UUID lastCrash = save(runningAfterCrashes(MAX_CRASHES - 1));
        UUID queued = save(ArchiveJobs.job(T0, QUEUED));

        RecoveredJobs recovered = lifecycle.recoverCrashedJobs(MAX_CRASHES);

        assertThat(recovered.requeued()).containsExactly(firstCrash);
        assertThat(recovered.failed()).containsExactly(lastCrash);
        assertThat(reload(firstCrash)).extracting(ArchiveJob::getStatus, ArchiveJob::getCrashCount)
                .containsExactly(QUEUED, 1);
        ArchiveJob abandoned = reload(lastCrash);
        assertThat(abandoned.getStatus()).isEqualTo(FAILED);
        assertThat(abandoned.getCrashCount()).isEqualTo(MAX_CRASHES);
        assertThat(abandoned.getErrorSummary())
                .isEqualTo(ArchiveJobLifecycle.CRASH_LIMIT_SUMMARY.formatted(MAX_CRASHES));
        assertThat(reload(queued).getCrashCount()).isZero();
    }

    @Test
    @DisplayName("recordCrash requeues a running job, and fails it on reaching the limit")
    void recordsACrash() {
        UUID job = save(runningAfterCrashes(MAX_CRASHES - 2));

        assertThat(lifecycle.recordCrash(job, MAX_CRASHES)).isTrue();
        assertThat(reload(job).getStatus()).isEqualTo(QUEUED);

        lifecycle.claimNextQueuedJob();
        assertThat(lifecycle.recordCrash(job, MAX_CRASHES)).isFalse();
        assertThat(reload(job)).extracting(ArchiveJob::getStatus, ArchiveJob::getCrashCount)
                .containsExactly(FAILED, MAX_CRASHES);
    }

    /** A running job that crashed the given number of times before. */
    private static ArchiveJob runningAfterCrashes(int crashes) {
        ArchiveJob job = ArchiveJobs.job(T0, RUNNING);
        for (int i = 0; i < crashes; i++) {
            job.recordCrash(T0);
            job.requeueAfterInterruption(T0);
            job.start(T0);
        }
        return job;
    }

    private UUID save(ArchiveJob job) {
        return jobs.saveAndFlush(job).getId();
    }

    private long savePage(ArchivePage page) {
        return pages.saveAndFlush(page).getId();
    }

    /** Reads the job back from the database rather than from the persistence context. */
    private ArchiveJob reload(UUID job) {
        entityManager.flush();
        entityManager.clear();
        return jobs.findById(job).orElseThrow();
    }

    private ArchivePage reloadPage(long page) {
        entityManager.flush();
        entityManager.clear();
        return pages.findById(page).orElseThrow();
    }
}
