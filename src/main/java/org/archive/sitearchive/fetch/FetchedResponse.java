package org.archive.sitearchive.fetch;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.net.URI;
import java.util.Collection;

/**
 * Final (non-redirect) response of a fetch. The body is only read for 2xx responses.
 *
 * @param finalUrl    the URL that produced this response, after redirects
 * @param contentType raw Content-Type header, or {@code null}
 */
public record FetchedResponse(URI finalUrl, int statusCode, String contentType, byte[] body) {

    static boolean isSuccessful(int statusCode) {
        return HttpStatusCode.valueOf(statusCode).is2xxSuccessful();
    }

    public FetchedResponse requireSuccess() throws FetchException {
        if (!isSuccessful(statusCode)) {
            throw FetchException.forHttpStatus(statusCode);
        }
        return this;
    }

    /** Whether the Content-Type is one of the given types, ignoring parameters such as the charset. */
    public boolean hasMediaType(Collection<MediaType> types) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return types.stream().anyMatch(mediaType::equalsTypeAndSubtype);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
