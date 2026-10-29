package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.ArchiveJobLifecycle;
import org.archive.sitearchive.archive.RecoveredJobs;
import org.archive.sitearchive.config.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fixed pool of virtual-thread workers that claim and process queued jobs. On start, jobs left RUNNING by the
 * previous process are recovered (assumes a single instance). On shutdown, each worker hands its job back to the
 * queue. A job stopped by an {@link Error} counts as a crash of that job, and the worker carries on.
 * {@link OutOfMemoryError} is left to {@code -XX:+ExitOnOutOfMemoryError}, which stops the whole JVM before the
 * error gets here; {@code bootRun} sets it, and the README asks for it when running the jar. Without the flag, an
 * {@code OutOfMemoryError} is counted as a crash like any other {@code Error}.
 */
@Component
class ArchiveWorkers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ArchiveWorkers.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    static final String INTERNAL_ERROR_SUMMARY = "An internal error stopped the archive job. The error has been logged.";

    private final ArchiveJobLifecycle lifecycle;
    private final ArchiveJobProcessor processor;
    private final int concurrency;
    private final Duration pollInterval;
    private final int maxCrashesPerJob;

    private ExecutorService executor;
    private volatile boolean running;

    ArchiveWorkers(ArchiveJobLifecycle lifecycle, ArchiveJobProcessor processor, ArchiveProperties properties) {
        this.lifecycle = lifecycle;
        this.processor = processor;
        this.concurrency = properties.worker().concurrency();
        this.pollInterval = properties.worker().pollInterval();
        this.maxCrashesPerJob = properties.worker().maxCrashesPerJob();
    }

    @Override
    public void start() {
        RecoveredJobs recovered = lifecycle.recoverCrashedJobs(maxCrashesPerJob);
        if (!recovered.requeued().isEmpty()) {
            log.info("Requeued {} archive jobs that were running when the service stopped unexpectedly: {}",
                    recovered.requeued().size(), recovered.requeued());
        }
        if (!recovered.failed().isEmpty()) {
            log.warn("Failed {} archive jobs that were stopped {} times by a crash or a fatal error: {}",
                    recovered.failed().size(), maxCrashesPerJob, recovered.failed());
        }
        running = true;
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("archive-worker-", 1).factory());
        for (int i = 0; i < concurrency; i++) {
            executor.execute(this::processJobsUntilStopped);
        }
        log.info("Started {} archive workers", concurrency);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("Archive workers did not stop within {}s", SHUTDOWN_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void processJobsUntilStopped() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<UUID> job = claimNextJob();
                if (job.isPresent()) {
                    process(job.get());
                } else {
                    Thread.sleep(pollInterval);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Error e) {
                // Outside a job, e.g. while claiming one. Pausing keeps an error that recurs from spinning the loop.
                log.error("Archive worker hit a fatal error; trying again in {}", pollInterval, e);
                pause(pollInterval);
            }
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<UUID> claimNextJob() {
        try {
            return lifecycle.claimNextQueuedJob();
        } catch (RuntimeException e) {
            log.error("Archive worker could not claim a job", e);
            return Optional.empty();
        }
    }

    private void process(UUID jobId) throws InterruptedException {
        try {
            processor.process(jobId);
        } catch (InterruptedException e) {
            handBack(jobId);
            throw e;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                handBack(jobId);
                return;
            }
            log.error("Archive job {} stopped unexpectedly", jobId, e);
            markFailed(jobId);
        } catch (Error e) {
            // Such as a StackOverflowError on a deeply nested page. The job may be at fault, or only have been running
            // when it happened, so it is not failed straight away.
            log.error("Archive job {} stopped by a fatal error", jobId, e);
            recordCrash(jobId);
        }
    }

    /**
     * Returns the job of a worker stopped for a shutdown to the queue. The interrupt is put aside meanwhile, as it
     * would abort the database call.
     */
    private void handBack(UUID jobId) {
        boolean interrupted = Thread.interrupted();
        try {
            lifecycle.requeueStoppedJob(jobId);
            log.info("Archive job {} was interrupted and has been requeued", jobId);
        } catch (RuntimeException e) {
            log.warn("Archive job {} was interrupted and could not be requeued; it stays RUNNING and counts as a crash",
                    jobId, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void markFailed(UUID jobId) {
        try {
            lifecycle.failJob(jobId, INTERNAL_ERROR_SUMMARY);
        } catch (RuntimeException e) {
            log.error("Archive job {} could not be marked as failed and stays RUNNING until the application restarts",
                    jobId, e);
        }
    }

    private void recordCrash(UUID jobId) {
        try {
            if (lifecycle.recordCrash(jobId, maxCrashesPerJob)) {
                log.info("Archive job {} has been requeued", jobId);
            } else {
                log.warn("Archive job {} failed: it was stopped {} times by a crash or a fatal error",
                        jobId, maxCrashesPerJob);
            }
        } catch (RuntimeException e) {
            log.error("Archive job {} could not be requeued and stays RUNNING until the application restarts",
                    jobId, e);
        }
    }
}
