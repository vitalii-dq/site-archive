package org.archive.sitearchive.fetch;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import java.net.URI;
import java.util.Collection;

/**
 * Final (non-redirect) response of a fetch.
 *
 * @param finalUrl    the URL that produced this response, after redirects
 * @param contentType raw Content-Type header, or {@code null}
 * @param body        the body, in memory or in a file; {@code null} unless the response was successful (2xx), as
 *                    other bodies are not read
 * @param <B>         how the body is held
 */
public record FetchedResponse<B>(URI finalUrl, int statusCode, String contentType, B body) {

    static boolean isSuccessful(int statusCode) {
        return HttpStatusCode.valueOf(statusCode).is2xxSuccessful();
    }

    public FetchedResponse<B> requireSuccess() throws FetchException {
        if (!isSuccessful(statusCode)) {
            throw FetchException.forHttpStatus(statusCode);
        }
        return this;
    }

    /** See {@link MediaTypes#matches}. */
    public boolean hasMediaType(Collection<MediaType> types) {
        return MediaTypes.matches(contentType, types);
    }
}
