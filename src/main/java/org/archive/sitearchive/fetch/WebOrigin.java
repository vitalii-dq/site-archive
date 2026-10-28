package org.archive.sitearchive.fetch;

import java.net.URI;
import java.util.Locale;

public record WebOrigin(String scheme, String host, int port) {

    public static WebOrigin of(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("URL has no origin: " + uri);
        }
        return new WebOrigin(
                uri.getScheme().toLowerCase(Locale.ROOT),
                uri.getHost().toLowerCase(Locale.ROOT),
                HttpUrls.effectivePort(uri));
    }

    public static WebOrigin parse(String origin) {
        return of(URI.create(origin));
    }

    public boolean contains(URI uri) {
        return uri.getScheme() != null && uri.getHost() != null && equals(of(uri));
    }

    @Override
    public String toString() {
        return scheme + "://" + host + (port == HttpUrls.defaultPort(scheme) ? "" : ":" + port);
    }
}
