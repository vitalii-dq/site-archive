package org.archive.sitearchive.fetch;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.Collection;

public final class MediaTypes {

    private MediaTypes() {
    }

    /**
     * Whether a raw Content-Type header is one of the given types, ignoring parameters such as the charset. A missing
     * or invalid header matches nothing.
     */
    public static boolean matches(String contentType, Collection<MediaType> types) {
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
