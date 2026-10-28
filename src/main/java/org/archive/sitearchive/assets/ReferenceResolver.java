package org.archive.sitearchive.assets;

import org.archive.sitearchive.fetch.HttpUrls;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Decides what replaces one reference found in HTML or CSS (an attribute value or a CSS URL): the path of the
 * local copy if there is one, otherwise the reference made absolute so that it still reaches the original site
 * from inside the archive. References that are already absolute and have no local copy are left as they are.
 */
final class ReferenceResolver implements UnaryOperator<String> {

    private final URI base;
    private final Function<URI, String> localCopy;

    /**
     * @param base      URL that relative references are resolved against
     * @param localCopy given the canonical URL of a reference, the path of its local copy, or {@code null}
     */
    ReferenceResolver(URI base, Function<URI, String> localCopy) {
        this.base = base;
        this.localCopy = localCopy;
    }

    /** Returns the replacement, or {@code null} to keep the reference as it is. */
    @Override
    public String apply(String reference) {
        String value = reference.strip();
        if (value.isEmpty() || value.startsWith("#")) {
            return null;
        }
        URI uri;
        URI resolved;
        try {
            uri = new URI(value);
            resolved = base.resolve(uri);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
        if (!HttpUrls.isHttp(resolved)) {
            return null; // data:, mailto:, javascript: and the like
        }
        String local = HttpUrls.parse(resolved.toString()).map(localCopy).orElse(null);
        String absolute = resolved.toASCIIString();
        if (local != null) {
            int fragmentStart = absolute.indexOf('#');
            return fragmentStart == -1 ? local : local + absolute.substring(fragmentStart);
        }
        return uri.isAbsolute() ? null : absolute;
    }

    /** Collects the canonical URL of every reference into {@code found}, without replacing anything. */
    static ReferenceResolver collecting(URI base, Collection<URI> found) {
        return new ReferenceResolver(base, url -> {
            found.add(url);
            return null;
        });
    }
}
