package org.archive.sitearchive.archive;

public enum ArchiveJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED;

    public boolean isFinished() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED;
    }

    public boolean hasArchive() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS;
    }

    boolean canTransitionTo(ArchiveJobStatus target) {
        return switch (this) {
            case QUEUED -> target == RUNNING;
            case RUNNING -> target == QUEUED || target.isFinished();
            default -> false;
        };
    }
}
