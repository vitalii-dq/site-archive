package org.archive.sitearchive.fetch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

public final class HttpUrls {

    public static final int MAX_LENGTH = 2048;

    private HttpUrls() {
    }

    /**
     * Parses an absolute http(s) URL with a host and without credentials, returning its canonical form:
     * lower-case scheme and host, no default port, no fragment, and "/" for an empty path. Path and query
     * are kept as written. Returns empty for anything else.
     */
    public static Optional<URI> parse(String value) {
        String url = value == null ? "" : value.strip();
        if (url.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url);
            if (!isHttp(uri) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(canonical(uri));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    public static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    static int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : defaultPort(uri.getScheme());
    }

    static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static URI canonical(URI uri) throws URISyntaxException {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String port = effectivePort(uri) == defaultPort(scheme) ? "" : ":" + uri.getPort();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new URI(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port + path + query);
    }
}
