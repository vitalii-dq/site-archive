package org.archive.sitearchive.storage;

/**
 * @param fileName captured file relative to the job directory (the same path is used inside the ZIP)
 */
public record StoredFile(String fileName, long sizeBytes) {
}
