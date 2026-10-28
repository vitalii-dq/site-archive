package org.archive.sitearchive.archive.api.dto;

import org.archive.sitearchive.archive.ArchivePage;
import org.archive.sitearchive.archive.PageStatus;

import java.time.Instant;

/**
 * @param fileName path of the captured page inside the ZIP, if captured
 */
public record ArchivePageResponse(
        long id,
        String sourceUrl,
        PageStatus status,
        int attempts,
        Integer httpStatus,
        String finalUrl,
        String contentType,
        Long sizeBytes,
        String fileName,
        String error,
        Instant lastAttemptAt,
        Instant capturedAt) {

    public static ArchivePageResponse from(ArchivePage page) {
        return new ArchivePageResponse(
                page.getId(),
                page.getSourceUrl(),
                page.getStatus(),
                page.getAttempts(),
                page.getHttpStatus(),
                page.getFinalUrl(),
                page.getContentType(),
                page.getSizeBytes(),
                page.getFileName(),
                page.getErrorMessage(),
                page.getLastAttemptAt(),
                page.getCapturedAt());
    }
}
