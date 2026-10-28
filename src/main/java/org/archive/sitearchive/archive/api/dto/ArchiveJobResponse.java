package org.archive.sitearchive.archive.api.dto;

import org.archive.sitearchive.archive.ArchiveJob;
import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.archive.sitearchive.archive.PageCounts;

import java.time.Instant;
import java.util.UUID;

/**
 * @param sitemapUrlCount  distinct entries listed in the sitemap ({@code null} until it has been read)
 * @param excludedUrlCount entries not captured: invalid, outside the site origin, or beyond {@code maxPages};
 *                         {@code sitemapUrlCount} = pages + {@code excludedUrlCount}
 */
public record ArchiveJobResponse(
        UUID id,
        ArchiveJobStatus status,
        String sitemapUrl,
        int maxPages,
        String siteOrigin,
        Integer sitemapUrlCount,
        Integer excludedUrlCount,
        ProgressResponse progress,
        String errorSummary,
        boolean archiveAvailable,
        Long archiveSizeBytes,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public static ArchiveJobResponse from(ArchiveJob job, PageCounts pageCounts) {
        return new ArchiveJobResponse(
                job.getId(),
                job.getStatus(),
                job.getSitemapUrl(),
                job.getMaxPages(),
                job.getSiteOrigin(),
                job.getSitemapUrlCount(),
                job.getExcludedUrlCount(),
                ProgressResponse.from(pageCounts),
                job.getErrorSummary(),
                job.getStatus().hasArchive(),
                job.getArchiveSizeBytes(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt());
    }
}
