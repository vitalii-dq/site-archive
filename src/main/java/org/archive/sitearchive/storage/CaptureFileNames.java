package org.archive.sitearchive.storage;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates file names for captured pages and assets. Remote paths are never used as file system paths: the
 * name is built from the page id or the asset's number within the run, which keeps it unique, plus a sanitised
 * slug that only helps humans browsing the archive.
 */
final class CaptureFileNames {

    static final String PAGES_DIRECTORY = "pages";
    static final String ASSETS_DIRECTORY = "assets";

    private static final int MAX_SLUG_LENGTH = 50;
    private static final Pattern EXTENSION = Pattern.compile("\\.([a-z0-9]{1,5})$");

    /** Extensions for common asset types. Browsers opening files from disk derive the type from the extension. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("text/css", "css"),
            Map.entry("text/javascript", "js"),
            Map.entry("application/javascript", "js"),
            Map.entry("application/x-javascript", "js"),
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/avif", "avif"),
            Map.entry("image/svg+xml", "svg"),
            Map.entry("image/x-icon", "ico"),
            Map.entry("image/vnd.microsoft.icon", "ico"),
            Map.entry("font/woff", "woff"),
            Map.entry("font/woff2", "woff2"),
            Map.entry("font/ttf", "ttf"),
            Map.entry("font/otf", "otf"),
            Map.entry("application/font-woff", "woff"),
            Map.entry("application/x-font-ttf", "ttf"),
            Map.entry("application/vnd.ms-fontobject", "eot"));

    private CaptureFileNames() {
    }

    /** For example {@code pages/42-blog-hello-world.html}. */
    static String forPage(long pageId, URI sourceUrl) {
        return PAGES_DIRECTORY + "/" + pageId + "-" + slug(sourceUrl) + ".html";
    }

    /**
     * For example {@code assets/7-css-site.css}. The extension comes from the Content-Type if it is a known asset
     * type, otherwise from the URL (fonts are often served as {@code application/octet-stream}).
     */
    static String forAsset(int assetNumber, URI sourceUrl, String contentType) {
        return ASSETS_DIRECTORY + "/" + assetNumber + "-" + slug(sourceUrl) + "." + extension(sourceUrl, contentType);
    }

    private static String extension(URI sourceUrl, String contentType) {
        String known = contentType == null ? null : EXTENSIONS.get(mediaType(contentType));
        if (known != null) {
            return known;
        }
        var matcher = EXTENSION.matcher(lowerCasePath(sourceUrl));
        return matcher.find() ? matcher.group(1) : "bin";
    }

    private static String mediaType(String contentType) {
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return mediaType.getType() + "/" + mediaType.getSubtype();
        } catch (InvalidMediaTypeException e) {
            return "";
        }
    }

    private static String slug(URI sourceUrl) {
        String slug = EXTENSION.matcher(lowerCasePath(sourceUrl)).replaceFirst("")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "index" : slug;
    }

    private static String lowerCasePath(URI url) {
        return url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
    }
}
