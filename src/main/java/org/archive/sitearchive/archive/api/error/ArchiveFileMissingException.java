package org.archive.sitearchive.archive.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;

public class ArchiveFileMissingException extends ErrorResponseException {

    public ArchiveFileMissingException(UUID archiveId) {
        super(HttpStatus.GONE);
        setTitle("Archive file missing");
        setDetail("The archive file for " + archiveId + " is no longer available");
    }
}
