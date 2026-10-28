package org.archive.sitearchive.storage;

import org.springframework.web.util.HtmlUtils;

/** Renders the archive's {@code index.html}, linking to every captured page. */
final class IndexPageRenderer {

    private IndexPageRenderer() {
    }

    static String render(ArchiveManifest manifest) {
        StringBuilder links = new StringBuilder();
        for (ArchiveManifest.Entry page : manifest.capturedPages()) {
            links.append("    <li><a href=\"").append(escape(page.file())).append("\">")
                    .append(escape(page.sourceUrl())).append("</a></li>\n");
        }
        int captured = manifest.capturedPages().size();
        String skipped = manifest.assetsSkipped() == 0
                ? ""
                : " (at least %d more not attempted: the asset limit was reached)".formatted(manifest.assetsSkipped());
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Archive %s</title>
                </head>
                <body>
                  <h1>Snapshot of %s</h1>
                  <p>Sitemap: %s<br>Generated: %s<br>Captured pages: %d of %d<br>Captured assets: %d of %d%s</p>
                  <p><em>%s</em></p>
                  <p>Details for every page, including failures, are in <a href="manifest.json">manifest.json</a>.</p>
                  <ul>
                %s  </ul>
                </body>
                </html>
                """.formatted(
                manifest.archiveId(),
                escape(manifest.siteOrigin()),
                escape(manifest.sitemapUrl()),
                manifest.generatedAt(),
                captured,
                manifest.pages().size(),
                manifest.capturedAssets().size(),
                manifest.assets().size(),
                skipped,
                escape(ArchiveManifest.SNAPSHOT_NOTICE),
                links);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
