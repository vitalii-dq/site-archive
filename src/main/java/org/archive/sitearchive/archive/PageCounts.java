package org.archive.sitearchive.archive;

import java.util.UUID;

/** Page totals per status for one job, derived from persisted page state rather than stored counters. */
public record PageCounts(UUID archiveJobId, long pending, long succeeded, long failed) {

    /** The counts of a job that has no pages yet. */
    public static PageCounts none(UUID archiveJobId) {
        return new PageCounts(archiveJobId, 0, 0, 0);
    }

    public long total() {
        return pending + succeeded + failed;
    }
}
