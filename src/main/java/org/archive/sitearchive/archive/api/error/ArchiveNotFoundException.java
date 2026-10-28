package org.archive.sitearchive.archive.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;

public class ArchiveNotFoundException extends ErrorResponseException {

    public ArchiveNotFoundException(UUID archiveId) {
        super(HttpStatus.NOT_FOUND);
        setTitle("Archive not found");
        setDetail("Archive " + archiveId + " was not found");
    }
}
