package org.archive.sitearchive.archive;

import jakarta.persistence.EntityManager;
import org.archive.sitearchive.PostgresTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.archive.sitearchive.archive.ArchiveJobStatus.FAILED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.QUEUED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PostgresTestConfiguration.class)
class ArchiveJobRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Autowired
    private ArchiveJobRepository jobs;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("lockOldestQueued returns the oldest QUEUED job, ignoring older jobs in other statuses")
    void locksTheOldestQueuedJob() {
        save(T0, RUNNING);
        save(T0.plusSeconds(1), FAILED);
        UUID oldestQueued = save(T0.plusSeconds(2), QUEUED);
        save(T0.plusSeconds(3), QUEUED);

        assertThat(jobs.lockOldestQueued()).map(ArchiveJob::getId).contains(oldestQueued);
    }

    @Test
    @DisplayName("lockOldestQueued returns nothing when no job is queued")
    void returnsNothingWhenNoJobIsQueued() {
        save(T0, RUNNING);

        assertThat(jobs.lockOldestQueued()).isEmpty();
    }

    @Test
    @DisplayName("lockOldestQueued returns a managed job, so that a status change is saved with the transaction")
    void returnsAManagedJob() {
        UUID queued = save(T0, QUEUED);
        entityManager.clear();

        jobs.lockOldestQueued().orElseThrow().start(T0.plusSeconds(5));
        entityManager.flush();
        entityManager.clear();

        assertThat(jobs.findById(queued)).map(ArchiveJob::getStatus).contains(RUNNING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("lockOldestQueued in a second transaction skips the job the first one holds and returns the next, "
            + "without waiting")
    void skipsAJobLockedByAnotherTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID oldest = save(T0, QUEUED);
        UUID next = save(T0.plusSeconds(1), QUEUED);
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(jobs.lockOldestQueued()).map(ArchiveJob::getId).contains(oldest);

                // Another thread, so another transaction, while this one still holds the lock. Without SKIP LOCKED it
                // would wait for this transaction to end, which in turn waits for it, so the timeout fails the test.
                Optional<UUID> claimedMeanwhile = CompletableFuture
                        .supplyAsync(() -> transaction.execute(other -> jobs.lockOldestQueued().map(ArchiveJob::getId)))
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();

                assertThat(claimedMeanwhile).contains(next);
            });
        } finally {
            jobs.deleteAll();
        }
    }

    @Test
    @DisplayName("findByStatus with paging returns only jobs in the status, in the requested order and page")
    void pagesJobsInAStatus() {
        UUID oldest = save(T0, FAILED);
        UUID middle = save(T0.plusSeconds(1), FAILED);
        UUID newest = save(T0.plusSeconds(2), FAILED);
        save(T0.plusSeconds(3), QUEUED);

        Page<ArchiveJob> first = jobs.findByStatus(FAILED, PageRequest.of(0, 2, NEWEST_FIRST));
        Page<ArchiveJob> second = jobs.findByStatus(FAILED, PageRequest.of(1, 2, NEWEST_FIRST));

        assertThat(first.map(ArchiveJob::getId)).containsExactly(newest, middle);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(second.map(ArchiveJob::getId)).containsExactly(oldest);
    }

    @Test
    @DisplayName("findByStatus returns all jobs in the status")
    void findsAllJobsInAStatus() {
        UUID first = save(T0, RUNNING);
        UUID second = save(T0.plusSeconds(1), RUNNING);
        save(T0.plusSeconds(2), QUEUED);

        assertThat(jobs.findByStatus(RUNNING)).extracting(ArchiveJob::getId).containsExactlyInAnyOrder(first, second);
    }

    private UUID save(Instant createdAt, ArchiveJobStatus status) {
        return jobs.saveAndFlush(job(createdAt, status)).getId();
    }

    private static ArchiveJob job(Instant createdAt, ArchiveJobStatus status) {
        ArchiveJob job = ArchiveJob.queue("https://example.org/sitemap.xml", 10, createdAt);
        switch (status) {
            case QUEUED -> {
            }
            case RUNNING -> job.start(createdAt);
            case FAILED -> {
                job.start(createdAt);
                job.fail("failed", createdAt);
            }
            default -> throw new IllegalArgumentException("Not needed by these tests: " + status);
        }
        return job;
    }
}
