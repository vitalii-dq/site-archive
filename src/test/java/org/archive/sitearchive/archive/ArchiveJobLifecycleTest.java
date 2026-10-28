package org.archive.sitearchive.archive;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveJobLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final int MAX_CRASHES = 3;

    private final ArchiveJobRepository jobs = mock(ArchiveJobRepository.class);
    private final ArchiveJobLifecycle lifecycle = new ArchiveJobLifecycle(jobs, mock(ArchivePageRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void requeuesACrashedJobAndCountsTheCrash() {
        ArchiveJob job = runningJob(0);

        RecoveredJobs recovered = lifecycle.recoverCrashedJobs(MAX_CRASHES);

        assertThat(recovered.requeued()).containsExactly(job.getId());
        assertThat(recovered.failed()).isEmpty();
        assertThat(job.getStatus()).isEqualTo(ArchiveJobStatus.QUEUED);
        assertThat(job.getCrashCount()).isEqualTo(1);
    }

    @Test
    void failsAJobOnItsLastAllowedCrash() {
        ArchiveJob job = runningJob(MAX_CRASHES - 1);

        RecoveredJobs recovered = lifecycle.recoverCrashedJobs(MAX_CRASHES);

        assertThat(recovered.requeued()).isEmpty();
        assertThat(recovered.failed()).containsExactly(job.getId());
        assertThat(job.getStatus()).isEqualTo(ArchiveJobStatus.FAILED);
        assertThat(job.getCrashCount()).isEqualTo(MAX_CRASHES);
        assertThat(job.getErrorSummary()).isEqualTo(ArchiveJobLifecycle.CRASH_LIMIT_SUMMARY.formatted(MAX_CRASHES));
        assertThat(job.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void countsACrashOfARunningJob() {
        ArchiveJob job = runningJob(MAX_CRASHES - 2);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        assertThat(lifecycle.recordCrash(job.getId(), MAX_CRASHES)).isTrue();
        assertThat(job.getStatus()).isEqualTo(ArchiveJobStatus.QUEUED);

        job.start(NOW);
        assertThat(lifecycle.recordCrash(job.getId(), MAX_CRASHES)).isFalse();
        assertThat(job.getStatus()).isEqualTo(ArchiveJobStatus.FAILED);
        assertThat(job.getCrashCount()).isEqualTo(MAX_CRASHES);
    }

    @Test
    void requeuesAStoppedJobWithoutCountingACrash() {
        ArchiveJob job = runningJob(1);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        lifecycle.requeueStoppedJob(job.getId());

        assertThat(job.getStatus()).isEqualTo(ArchiveJobStatus.QUEUED);
        assertThat(job.getCrashCount()).isEqualTo(1);
    }

    @Test
    void leavesAStoppedJobThatAlreadyFinishedAsItIs() {
        ArchiveJob job = runningJob(0);
        job.fail("done", NOW);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        lifecycle.requeueStoppedJob(job.getId());

        assertThat(job.getStatus()).isEqualTo(ArchiveJobStatus.FAILED);
    }

    /** A job left RUNNING by a previous process, after the given number of earlier crashes. */
    private ArchiveJob runningJob(int earlierCrashes) {
        ArchiveJob job = ArchiveJob.queue("https://example.org/sitemap.xml", 10, NOW);
        job.start(NOW);
        for (int i = 0; i < earlierCrashes; i++) {
            job.recordCrash(NOW);
            job.requeueAfterInterruption(NOW);
            job.start(NOW);
        }
        when(jobs.findByStatus(ArchiveJobStatus.RUNNING)).thenReturn(List.of(job));
        return job;
    }
}
