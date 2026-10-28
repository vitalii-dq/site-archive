package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.ArchiveJobLifecycle;
import org.archive.sitearchive.config.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fixed pool of virtual-thread workers that claim and process queued jobs. On start, jobs left RUNNING by the
 * previous process are requeued (assumes a single instance); on shutdown, interrupted jobs stay RUNNING.
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
    private final Duration errorPause;

    private ExecutorService executor;
    private volatile boolean running;

    ArchiveWorkers(ArchiveJobLifecycle lifecycle, ArchiveJobProcessor processor, ArchiveProperties properties) {
        this.lifecycle = lifecycle;
        this.processor = processor;
        this.concurrency = properties.worker().concurrency();
        this.pollInterval = properties.worker().pollInterval();
        this.errorPause = properties.worker().errorPause();
    }

    @Override
    public void start() {
        List<UUID> requeued = lifecycle.requeueInterruptedJobs();
        if (!requeued.isEmpty()) {
            log.info("Requeued {} interrupted archive jobs: {}", requeued.size(), requeued);
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
                log.error("Archive worker hit an unexpected error; pausing for {} before the next job", errorPause, e);
                pause(errorPause);
            }
        }
    }

    private void pause(Duration duration) {
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
            logInterrupted(jobId);
            throw e;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                logInterrupted(jobId);
                return;
            }
            log.error("Archive job {} stopped unexpectedly", jobId, e);
            markFailed(jobId);
        } catch (Error e) {
            log.error("Archive job {} stopped by an unexpected error", jobId);
            markFailed(jobId);
            throw e;
        }
    }

    private void logInterrupted(UUID jobId) {
        log.info("Archive job {} was interrupted and will resume after a restart", jobId);
    }

    private void markFailed(UUID jobId) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            lifecycle.failJob(jobId, INTERNAL_ERROR_SUMMARY);
        } catch (RuntimeException e) {
            log.error("Archive job {} could not be marked as failed and stays RUNNING until the application restarts",
                    jobId, e);
        }
    }
}
