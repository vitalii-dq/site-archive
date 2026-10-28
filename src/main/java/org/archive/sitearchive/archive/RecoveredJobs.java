package org.archive.sitearchive.archive;

import java.util.List;
import java.util.UUID;

/**
 * Jobs left RUNNING by a previous process, after recovery.
 *
 * @param requeued jobs queued to run again
 * @param failed   jobs that reached the crash limit and were marked as failed
 */
public record RecoveredJobs(List<UUID> requeued, List<UUID> failed) {
}
