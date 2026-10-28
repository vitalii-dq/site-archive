package org.archive.sitearchive.storage;

import org.archive.sitearchive.assets.CssAssetLinks;
import org.archive.sitearchive.assets.HtmlAssetLinks;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Points the references in captured pages and stylesheets at the assets captured into the same archive. */
final class ArchiveLinks {

    /** Canonical source URL of each captured asset → its path inside the archive. */
    private final Map<URI, String> assetFiles = new HashMap<>();

    ArchiveLinks(ArchiveManifest manifest) {
        for (ArchiveManifest.Asset asset : manifest.capturedAssets()) {
            assetFiles.put(URI.create(asset.sourceUrl()), asset.file());
        }
    }

    /** References resolve against the URL that produced the page, after redirects, as in a browser. */
    byte[] rewritePage(ArchiveManifest.Entry page, byte[] html) {
        return HtmlAssetLinks.rewrite(html, page.contentType(), URI.create(page.finalUrl()),
                url -> localPath(page.file(), url));
    }

    static boolean isStylesheet(ArchiveManifest.Asset asset) {
        return CssAssetLinks.isStylesheet(asset.contentType());
    }

    byte[] rewriteStylesheet(ArchiveManifest.Asset stylesheet, byte[] css) {
        return CssAssetLinks.rewrite(css, URI.create(stylesheet.finalUrl()), url -> localPath(stylesheet.file(), url));
    }

    /** The path of the asset's copy, relative to the referencing file, or {@code null} if it was not captured. */
    private String localPath(String fromFile, URI url) {
        String file = assetFiles.get(url);
        if (file == null) {
            return null;
        }
        Path relative = Path.of(fromFile).getParent().relativize(Path.of(file));
        return relative.toString().replace(File.separatorChar, '/');
    }
}
