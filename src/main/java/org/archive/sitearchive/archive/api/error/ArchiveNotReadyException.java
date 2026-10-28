package org.archive.sitearchive.archive.api.error;

import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;

public class ArchiveNotReadyException extends ErrorResponseException {

    public ArchiveNotReadyException(UUID archiveId, ArchiveJobStatus status) {
        super(HttpStatus.CONFLICT);
        setTitle("Archive not ready");
        setDetail(status == ArchiveJobStatus.FAILED
                ? "Archive " + archiveId + " failed and has no download"
                : "Archive " + archiveId + " is not ready for download yet (status " + status + ")");
        getBody().setProperty("archiveStatus", status);
    }
}
