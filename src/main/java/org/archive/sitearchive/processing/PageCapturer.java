package org.archive.sitearchive.processing;

import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** One capture attempt for one page: a successful, same-origin HTML response, streamed into a file. */
@Component
class PageCapturer {

    private static final List<MediaType> HTML_MEDIA_TYPES = List.of(MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML);

    private final HttpFetcher fetcher;
    private final long maxPageBytes;

    PageCapturer(HttpFetcher fetcher, ArchiveProperties properties) {
        this.fetcher = fetcher;
        this.maxPageBytes = properties.limits().maxPageSize().toBytes();
    }

    /** See {@link HttpFetcher#fetchToFile} for what {@code file} must be, and what is left in it on failure. */
    FetchedResponse<Path> capture(URI url, WebOrigin siteOrigin, Path file) throws FetchException, InterruptedException {
        FetchedResponse<Path> response = fetcher.fetchToFile(url, maxPageBytes, siteOrigin, file).requireSuccess();
        if (!response.hasMediaType(HTML_MEDIA_TYPES)) {
            String contentType = response.contentType() == null ? "none" : response.contentType();
            throw FetchException.permanent("Not an HTML page (Content-Type: " + contentType + ")", response.statusCode());
        }
        return response;
    }
}
