package org.archive.sitearchive.archive.api.dto;

import org.archive.sitearchive.archive.PageCounts;

public record ProgressResponse(long totalPages, long pendingPages, long succeededPages, long failedPages,
                               int percentComplete) {

    static ProgressResponse from(PageCounts counts) {
        long processed = counts.succeeded() + counts.failed();
        int percent = counts.total() == 0 ? 0 : (int) (processed * 100 / counts.total());
        return new ProgressResponse(counts.total(), counts.pending(), counts.succeeded(), counts.failed(), percent);
    }
}
