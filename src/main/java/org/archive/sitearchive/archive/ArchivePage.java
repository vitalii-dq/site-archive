package org.archive.sitearchive.archive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "archive_page")
public class ArchivePage {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_CONTENT_TYPE_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "archive_job_id", nullable = false, updatable = false)
    private UUID archiveJobId;

    @Column(name = "source_url", nullable = false, updatable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PageStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "final_url")
    private String finalUrl;

    @Column(name = "content_type")
    private String contentType;

    /** Captured file, relative to the job directory (the same path is used inside the ZIP). */
    @Column(name = "file_name")
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ArchivePage() {
    }

    static ArchivePage pending(UUID archiveJobId, String sourceUrl, Instant now) {
        ArchivePage page = new ArchivePage();
        page.archiveJobId = archiveJobId;
        page.sourceUrl = sourceUrl;
        page.status = PageStatus.PENDING;
        page.createdAt = now;
        return page;
    }

    void recordAttemptStarted(Instant now) {
        requirePendingStatus();
        attempts++;
        lastAttemptAt = now;
    }

    void markSucceeded(int httpStatus, String finalUrl, String contentType, String fileName, long sizeBytes, Instant now) {
        requirePendingStatus();
        this.status = PageStatus.SUCCEEDED;
        this.httpStatus = httpStatus;
        this.finalUrl = finalUrl;
        this.contentType = truncate(contentType, MAX_CONTENT_TYPE_LENGTH);
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.errorMessage = null;
        this.capturedAt = now;
    }

    void markFailed(Integer httpStatus, String errorMessage) {
        requirePendingStatus();
        this.status = PageStatus.FAILED;
        this.httpStatus = httpStatus;
        this.errorMessage = truncate(errorMessage, MAX_ERROR_LENGTH);
    }

    private void requirePendingStatus() {
        if (status != PageStatus.PENDING) {
            throw new IllegalStateException("Archive page %d is %s, expected %s".formatted(id, status, PageStatus.PENDING));
        }
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public Long getId() {
        return id;
    }

    public UUID getArchiveJobId() {
        return archiveJobId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public PageStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getFinalUrl() {
        return finalUrl;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
