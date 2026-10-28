package org.archive.sitearchive.sitemap;

import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.HttpUrls;
import org.archive.sitearchive.fetch.WebOrigin;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Downloads a sitemap and selects the pages to capture from it. */
@Component
public class SitemapReader {

    private static final String COMPRESSED_SITEMAP_MESSAGE =
            "Compressed (gzip) sitemaps are not supported. Submit the URL of the uncompressed XML sitemap.";
    private static final List<MediaType> GZIP_MEDIA_TYPES =
            List.of(MediaType.valueOf("application/gzip"), MediaType.valueOf("application/x-gzip"));

    private final HttpFetcher fetcher;
    private final SitemapParser parser;
    private final long maxSitemapBytes;

    public SitemapReader(HttpFetcher fetcher, SitemapParser parser, ArchiveProperties properties) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.maxSitemapBytes = properties.limits().maxSitemapSize().toBytes();
    }

    public SitemapUrls read(URI sitemapUrl, int maxPages) throws SitemapException, InterruptedException {
        if (sitemapUrl.getPath().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            throw new SitemapException(COMPRESSED_SITEMAP_MESSAGE, false);
        }
        FetchedResponse<byte[]> response;
        try {
            response = fetcher.fetch(sitemapUrl, maxSitemapBytes, null).requireSuccess();
        } catch (FetchException e) {
            throw new SitemapException("The sitemap could not be downloaded: " + e.getMessage(), e.isRetryable());
        }
        if (isGzip(response)) {
            throw new SitemapException(COMPRESSED_SITEMAP_MESSAGE, false);
        }
        List<String> locations = parser.parseLocations(response.body());
        SitemapUrls urls = select(locations, WebOrigin.of(response.finalUrl()), maxPages);
        if (urls.pageUrls().isEmpty()) {
            throw new SitemapException("The sitemap lists no valid page URLs on its origin " + urls.origin()
                    + " (" + urls.listedUrlCount() + " entries listed)", false);
        }
        return urls;
    }

    /**
     * Deduplicates the listed URLs and keeps the first {@code maxPages} that belong to the origin. Every other
     * entry counts as excluded, so listed = selected + excluded.
     */
    private static SitemapUrls select(List<String> locations, WebOrigin origin, int maxPages) {
        Set<URI> distinctUrls = new LinkedHashSet<>();
        Set<String> invalidEntries = new LinkedHashSet<>();
        for (String location : locations) {
            Optional<URI> url = HttpUrls.parse(location);
            if (url.isPresent()) {
                distinctUrls.add(url.get());
            } else {
                invalidEntries.add(location);
            }
        }
        List<URI> pageUrls = distinctUrls.stream().filter(origin::contains).limit(maxPages).toList();
        int listed = distinctUrls.size() + invalidEntries.size();
        return new SitemapUrls(origin, pageUrls, listed, listed - pageUrls.size());
    }

    private static boolean isGzip(FetchedResponse<byte[]> response) {
        byte[] body = response.body();
        boolean gzipMagic = body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
        return gzipMagic || response.hasMediaType(GZIP_MEDIA_TYPES);
    }
}
