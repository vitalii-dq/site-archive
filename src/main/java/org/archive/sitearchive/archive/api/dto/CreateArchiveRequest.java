package org.archive.sitearchive.archive.api.dto;

import jakarta.validation.constraints.Positive;
import org.archive.sitearchive.archive.api.validation.HttpUrl;
import org.archive.sitearchive.archive.api.validation.WithinMaxPagesLimit;

/**
 * @param maxPages optional; defaults to the configured default and may not exceed the configured limit
 */
public record CreateArchiveRequest(
        @HttpUrl String sitemapUrl,
        @Positive @WithinMaxPagesLimit Integer maxPages) {
}
