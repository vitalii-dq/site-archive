package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.ArchiveJobLifecycle;
import org.archive.sitearchive.config.ArchiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ArchiveWorkersTest {

    private final ArchiveJobLifecycle lifecycle = mock(ArchiveJobLifecycle.class);
    private final ArchiveJobProcessor processor = mock(ArchiveJobProcessor.class);
    private final ArchiveWorkers workers = new ArchiveWorkers(lifecycle, processor,
            new ArchiveProperties(null, null, null, null, new ArchiveProperties.Worker(1, Duration.ofMillis(10), Duration.ofMillis(10))));

    @AfterEach
    void stopWorkers() {
        if (workers.isRunning()) {
            workers.stop();
        }
    }

    @Test
    void marksAJobThatStoppedUnexpectedlyAsFailedAndMovesOn(CapturedOutput output) throws Exception {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(failing))
                .thenReturn(Optional.of(next))
                .thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database down")).when(processor).process(failing);

        workers.start();

        verify(processor, timeout(1000)).process(next);
        verify(lifecycle).failJob(failing, ArchiveWorkers.INTERNAL_ERROR_SUMMARY);
        assertThat(output).contains("Archive job " + failing + " stopped unexpectedly")
                .doesNotContain("could not claim");
    }

    @Test
    void reportsAJobThatCouldNotEvenBeMarkedAsFailed(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database down")).when(processor).process(job);
        doThrow(new IllegalStateException("database down")).when(lifecycle).failJob(any(), any());

        workers.start();

        verify(lifecycle, timeout(1000).atLeast(2)).claimNextQueuedJob();
        assertThat(output).contains("Archive job " + job + " could not be marked as failed and stays RUNNING");
    }

    @Test
    void retriesClaimingAfterAFailure(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());

        workers.start();

        verify(processor, timeout(1000)).process(job);
        assertThat(output).contains("Archive worker could not claim a job");
    }

    @Test
    void marksTheJobAsFailedAndKeepsTheWorkerAfterAnError(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(next))
                .thenReturn(Optional.empty());
        doThrow(new NoClassDefFoundError("gone")).when(processor).process(job);

        workers.start();

        verify(processor, timeout(1000)).process(next);
        verify(lifecycle).failJob(job, ArchiveWorkers.INTERNAL_ERROR_SUMMARY);
        assertThat(output).contains("Archive job " + job + " stopped by an unexpected error")
                .contains("Archive worker hit an unexpected error; pausing");
    }

    @Test
    void leavesAnInterruptedJobRunningAndStopsTheWorker(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(UUID.randomUUID()));
        doThrow(new InterruptedException()).when(processor).process(job);

        workers.start();

        verify(lifecycle, after(300).times(1)).claimNextQueuedJob();
        verify(lifecycle, never()).failJob(any(), any());
        assertThat(output).contains("Archive job " + job + " was interrupted and will resume after a restart");
    }
}
