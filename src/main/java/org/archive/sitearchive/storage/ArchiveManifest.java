package org.archive.sitearchive.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contents of {@code manifest.json}: what was requested and what happened to each page and asset.
 *
 * @param assetsSkipped assets found but not attempted because the per-archive limit was reached; at least this
 *                      many, as the skipped stylesheets were not read
 */
public record ArchiveManifest(
        UUID archiveId,
        String sitemapUrl,
        String siteOrigin,
        Instant generatedAt,
        String notice,
        List<Entry> pages,
        List<Asset> assets,
        int assetsSkipped) {

    public static final String SNAPSHOT_NOTICE = """
            Snapshot of the pages listed in the sitemap, together with the images, stylesheets, scripts and fonts \
            they use from the site's own origin. References to those files were rewritten to point to the copies \
            in this archive, so the page HTML and stylesheets are not byte-for-byte as served. Files from other \
            sites were not downloaded: they are still loaded from those sites. Files that scripts load at runtime, \
            and links between pages, were not rewritten.""";

    /**
     * @param file        path inside the archive, or {@code null} if it was not captured
     * @param contentType raw Content-Type header of the captured response
     */
    public record Entry(
            String sourceUrl,
            String status,
            String file,
            Integer httpStatus,
            String contentType,
            String finalUrl,
            int attempts,
            String error) {

        public boolean captured() {
            return file != null;
        }
    }

    /**
     * An image, stylesheet, script or font from the site's origin that a captured page or stylesheet uses.
     *
     * @param file        path inside the archive, or {@code null} if it could not be captured
     * @param finalUrl    the URL that produced the file, after redirects
     * @param contentType raw Content-Type header of the captured response
     */
    public record Asset(String sourceUrl, String file, String finalUrl, String contentType, String error) {

        public boolean captured() {
            return file != null;
        }
    }

    List<Entry> capturedPages() {
        return pages.stream().filter(Entry::captured).toList();
    }

    List<Asset> capturedAssets() {
        return assets.stream().filter(Asset::captured).toList();
    }
}
