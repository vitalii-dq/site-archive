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
     * are kept as written. Returns empty for anything else, including a canonical form longer than
     * {@link #MAX_LENGTH}, which is what the database stores.
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
            // Usually no longer than the input, but "/" is added for an empty path.
            URI canonical = canonical(uri);
            return canonical.toString().length() > MAX_LENGTH ? Optional.empty() : Optional.of(canonical);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a reference against a base URL as RFC 3986 does. {@link URI#resolve} differs for a reference that
     * is only a query, such as {@code ?page=2}: it drops the last segment of the base path instead of keeping it.
     */
    public static URI resolve(URI base, URI reference) {
        boolean queryOnly = reference.getScheme() == null && reference.getRawAuthority() == null
                && reference.getRawPath() != null && reference.getRawPath().isEmpty()
                && reference.getRawQuery() != null;
        if (!queryOnly || base.isOpaque() || base.getRawAuthority() == null) {
            return base.resolve(reference);
        }
        String fragment = reference.getRawFragment() == null ? "" : "#" + reference.getRawFragment();
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + base.getRawPath()
                + "?" + reference.getRawQuery() + fragment);
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
