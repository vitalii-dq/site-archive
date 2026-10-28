package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.ArchiveJobLifecycle;
import org.archive.sitearchive.archive.RecoveredJobs;
import org.archive.sitearchive.config.ArchiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ArchiveWorkersTest {

    private static final int MAX_CRASHES = 3;

    private final ArchiveJobLifecycle lifecycle = mock(ArchiveJobLifecycle.class);
    private final ArchiveJobProcessor processor = mock(ArchiveJobProcessor.class);
    private final ArchiveWorkers workers = new ArchiveWorkers(lifecycle, processor,
            new ArchiveProperties(null, null, null, null, new ArchiveProperties.Worker(1, Duration.ofMillis(10), MAX_CRASHES)));

    @BeforeEach
    void nothingToRecover() {
        when(lifecycle.recoverCrashedJobs(MAX_CRASHES)).thenReturn(new RecoveredJobs(List.of(), List.of()));
    }

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
    void countsAnErrorAsACrashOfTheJobAndMovesOn(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(next))
                .thenReturn(Optional.empty());
        doThrow(new StackOverflowError()).when(processor).process(job);
        when(lifecycle.recordCrash(job, MAX_CRASHES)).thenReturn(true);

        workers.start();

        verify(processor, timeout(1000)).process(next);
        verify(lifecycle).recordCrash(job, MAX_CRASHES);
        verify(lifecycle, never()).failJob(any(), any());
        assertThat(output).contains("Archive job " + job + " stopped by a fatal error")
                .contains("Archive job " + job + " has been requeued");
    }

    @Test
    void reportsAJobThatFailedOnReachingTheCrashLimit(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        doThrow(new StackOverflowError()).when(processor).process(job);
        when(lifecycle.recordCrash(job, MAX_CRASHES)).thenReturn(false);

        workers.start();

        verify(lifecycle, timeout(1000)).recordCrash(job, MAX_CRASHES);
        assertThat(output).contains("Archive job " + job + " failed: it was stopped 3 times");
    }

    @Test
    void keepsTheWorkerAfterAnErrorOutsideAJob(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenThrow(new NoClassDefFoundError("gone"))
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());

        workers.start();

        verify(processor, timeout(1000)).process(job);
        assertThat(output).contains("Archive worker hit a fatal error; trying again in");
    }

    @Test
    void requeuesAnInterruptedJobAndStopsTheWorker(CapturedOutput output) throws Exception {
        UUID job = UUID.randomUUID();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(UUID.randomUUID()));
        doThrow(new InterruptedException()).when(processor).process(job);

        workers.start();

        verify(lifecycle, timeout(1000)).requeueStoppedJob(job);
        verify(lifecycle, after(300).times(1)).claimNextQueuedJob();
        verify(lifecycle, never()).failJob(any(), any());
        assertThat(output).contains("Archive job " + job + " was interrupted and has been requeued");
    }

    @Test
    void requeuesAJobAbortedByAnInterruptWithTheInterruptPutAside() throws Exception {
        UUID job = UUID.randomUUID();
        AtomicReference<Boolean> interruptedWhileRequeuing = new AtomicReference<>();
        when(lifecycle.claimNextQueuedJob())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(UUID.randomUUID()));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("database call aborted");
        }).when(processor).process(job);
        doAnswer(invocation -> {
            interruptedWhileRequeuing.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(lifecycle).requeueStoppedJob(job);

        workers.start();

        verify(lifecycle, timeout(1000)).requeueStoppedJob(job);
        verify(lifecycle, after(300).times(1)).claimNextQueuedJob();
        verify(lifecycle, never()).failJob(any(), any());
        assertThat(interruptedWhileRequeuing.get()).isFalse();
    }

    @Test
    void recoversCrashedJobsBeforeStarting(CapturedOutput output) {
        UUID requeued = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        when(lifecycle.recoverCrashedJobs(MAX_CRASHES)).thenReturn(new RecoveredJobs(List.of(requeued), List.of(failed)));
        when(lifecycle.claimNextQueuedJob()).thenReturn(Optional.empty());

        workers.start();

        assertThat(output).contains("Requeued 1 archive jobs").contains(requeued.toString())
                .contains("Failed 1 archive jobs").contains(failed.toString());
    }
}
