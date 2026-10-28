package org.archive.sitearchive.archive;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ArchivePageRepository extends JpaRepository<ArchivePage, Long> {

    Page<ArchivePage> findByArchiveJobId(UUID archiveJobId, Pageable pageable);

    Page<ArchivePage> findByArchiveJobIdAndStatus(UUID archiveJobId, PageStatus status, Pageable pageable);

    /** Pages with the given status, projected onto a record whose components are named like the entity's fields. */
    <T> List<T> findByArchiveJobIdAndStatusOrderById(UUID archiveJobId, PageStatus status, Class<T> type);

    /** All pages of the job, projected onto a record whose components are named like the entity's fields. */
    <T> List<T> findByArchiveJobIdOrderById(UUID archiveJobId, Class<T> type);

    /** Jobs without pages have no row. */
    @Query("""
            SELECT new org.archive.sitearchive.archive.PageCounts(p.archiveJobId,
                    COUNT(p) FILTER (WHERE p.status = PENDING),
                    COUNT(p) FILTER (WHERE p.status = SUCCEEDED),
                    COUNT(p) FILTER (WHERE p.status = FAILED))
            FROM ArchivePage p
            WHERE p.archiveJobId IN :jobIds
            GROUP BY p.archiveJobId
            """)
    List<PageCounts> countPages(Collection<UUID> jobIds);
}
