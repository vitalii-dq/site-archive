package org.archive.sitearchive.storage;

/**
 * @param relativePath ZIP location relative to the storage root
 */
public record StoredArchive(String relativePath, long sizeBytes) {
}
