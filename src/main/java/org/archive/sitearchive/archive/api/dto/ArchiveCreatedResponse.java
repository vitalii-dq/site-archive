package org.archive.sitearchive.archive.api.dto;

import org.archive.sitearchive.archive.ArchiveJob;
import org.archive.sitearchive.archive.ArchiveJobStatus;

import java.time.Instant;
import java.util.UUID;

public record ArchiveCreatedResponse(UUID id, ArchiveJobStatus status, Instant createdAt) {

    public static ArchiveCreatedResponse from(ArchiveJob job) {
        return new ArchiveCreatedResponse(job.getId(), job.getStatus(), job.getCreatedAt());
    }
}
