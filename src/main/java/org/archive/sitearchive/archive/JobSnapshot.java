package org.archive.sitearchive.archive;

import org.archive.sitearchive.fetch.WebOrigin;

import java.net.URI;
import java.util.UUID;

/**
 * Immutable view of a job for the worker, read once per run.
 *
 * @param siteOrigin {@code null} until the sitemap has been processed
 */
public record JobSnapshot(UUID id, URI sitemapUrl, int maxPages, WebOrigin siteOrigin) {

    static JobSnapshot of(ArchiveJob job) {
        WebOrigin origin = job.isSitemapProcessed() ? WebOrigin.parse(job.getSiteOrigin()) : null;
        return new JobSnapshot(job.getId(), URI.create(job.getSitemapUrl()), job.getMaxPages(), origin);
    }

    public boolean sitemapProcessed() {
        return siteOrigin != null;
    }
}
