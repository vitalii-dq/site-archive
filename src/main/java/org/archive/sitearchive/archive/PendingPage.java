package org.archive.sitearchive.archive;

/** A page still to be captured, with the attempts already used up by earlier runs. */
public record PendingPage(long id, String sourceUrl, int attempts) {
}
