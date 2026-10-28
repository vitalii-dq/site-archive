package org.archive.sitearchive.archive;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArchiveJobRepository extends JpaRepository<ArchiveJob, UUID> {

    Page<ArchiveJob> findByStatus(ArchiveJobStatus status, Pageable pageable);

    List<ArchiveJob> findByStatus(ArchiveJobStatus status);

    /**
     * Locks the oldest queued job. The lock is only held by the caller's transaction, which must also change the
     * job's status before it ends; without one, the job would be unlocked at once and could be claimed twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT * FROM archive_job
            WHERE status = 'QUEUED'
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ArchiveJob> lockOldestQueued();
}
