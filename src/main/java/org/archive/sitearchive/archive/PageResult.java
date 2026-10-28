package org.archive.sitearchive.archive;

/**
 * Where a page stands after the capture stage, for the asset stage and the manifest. Components are named like
 * the entity's fields, so the repository can project onto this record.
 *
 * @param fileName captured file relative to the job directory, or {@code null} if the page was not captured
 */
public record PageResult(
        String sourceUrl,
        PageStatus status,
        String fileName,
        Integer httpStatus,
        String contentType,
        String finalUrl,
        int attempts,
        String errorMessage) {

    public boolean captured() {
        return fileName != null;
    }
}
