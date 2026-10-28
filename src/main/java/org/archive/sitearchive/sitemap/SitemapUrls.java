package org.archive.sitearchive.sitemap;

import org.archive.sitearchive.fetch.WebOrigin;

import java.net.URI;
import java.util.List;

/**
 * Result of reading a sitemap.
 *
 * @param origin           origin of the sitemap (after redirects); pages must belong to it
 * @param pageUrls         distinct in-origin URLs selected for capture, in sitemap order, at most maxPages
 * @param listedUrlCount   distinct entries listed in the sitemap
 * @param excludedUrlCount entries not captured: invalid, outside the origin, or beyond maxPages; so
 *                         {@code listedUrlCount = pageUrls.size() + excludedUrlCount}
 */
public record SitemapUrls(WebOrigin origin, List<URI> pageUrls, int listedUrlCount, int excludedUrlCount) {
}
