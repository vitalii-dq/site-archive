package org.archive.sitearchive.archive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "archive_job")
public class ArchiveJob {

    @Id
    private UUID id;

    @Column(name = "sitemap_url", nullable = false, updatable = false)
    private String sitemapUrl;

    @Column(name = "max_pages", nullable = false, updatable = false)
    private int maxPages;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ArchiveJobStatus status;

    @Column(name = "site_origin")
    private String siteOrigin;

    @Column(name = "sitemap_url_count")
    private Integer sitemapUrlCount;

    @Column(name = "excluded_url_count")
    private Integer excludedUrlCount;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "archive_file")
    private String archiveFile;

    @Column(name = "archive_size_bytes")
    private Long archiveSizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ArchiveJob() {
    }

    public static ArchiveJob queue(String sitemapUrl, int maxPages, Instant now) {
        ArchiveJob job = new ArchiveJob();
        job.id = UUID.randomUUID();
        job.sitemapUrl = Objects.requireNonNull(sitemapUrl);
        job.maxPages = maxPages;
        job.status = ArchiveJobStatus.QUEUED;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    void start(Instant now) {
        transitionTo(ArchiveJobStatus.RUNNING, now);
        if (startedAt == null) {
            startedAt = now;
        }
    }

    void requeueAfterInterruption(Instant now) {
        transitionTo(ArchiveJobStatus.QUEUED, now);
    }

    void recordSitemap(String siteOrigin, int sitemapUrlCount, int excludedUrlCount, Instant now) {
        requireStatus(ArchiveJobStatus.RUNNING);
        this.siteOrigin = siteOrigin;
        this.sitemapUrlCount = sitemapUrlCount;
        this.excludedUrlCount = excludedUrlCount;
        this.updatedAt = now;
    }

    boolean isSitemapProcessed() {
        return sitemapUrlCount != null;
    }

    void complete(boolean withErrors, String archiveFile, long archiveSizeBytes, String errorSummary, Instant now) {
        transitionTo(withErrors ? ArchiveJobStatus.COMPLETED_WITH_ERRORS : ArchiveJobStatus.COMPLETED, now);
        this.archiveFile = archiveFile;
        this.archiveSizeBytes = archiveSizeBytes;
        this.errorSummary = errorSummary;
        this.finishedAt = now;
    }

    void fail(String errorSummary, Instant now) {
        transitionTo(ArchiveJobStatus.FAILED, now);
        this.errorSummary = errorSummary;
        this.finishedAt = now;
    }

    private void transitionTo(ArchiveJobStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Archive job %s cannot move from %s to %s".formatted(id, status, target));
        }
        status = target;
        updatedAt = now;
    }

    private void requireStatus(ArchiveJobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Archive job %s is %s, expected %s".formatted(id, status, expected));
        }
    }

    public UUID getId() {
        return id;
    }

    public String getSitemapUrl() {
        return sitemapUrl;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public ArchiveJobStatus getStatus() {
        return status;
    }

    public String getSiteOrigin() {
        return siteOrigin;
    }

    public Integer getSitemapUrlCount() {
        return sitemapUrlCount;
    }

    public Integer getExcludedUrlCount() {
        return excludedUrlCount;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public String getArchiveFile() {
        return archiveFile;
    }

    public Long getArchiveSizeBytes() {
        return archiveSizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
