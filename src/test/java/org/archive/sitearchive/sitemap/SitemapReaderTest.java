package org.archive.sitearchive.sitemap;

import org.archive.sitearchive.config.TestArchiveProperties;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SitemapReaderTest {

    private static final URI SITEMAP = URI.create("https://example.org/sitemap.xml");
    private static final byte[] GZIP_MAGIC = {0x1f, (byte) 0x8b, 0x08};

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final SitemapReader reader = new SitemapReader(fetcher, new SitemapParser(), TestArchiveProperties.defaults());

    @Test
    @DisplayName("Selects distinct pages on the origin up to maxPages and counts every other entry as excluded")
    void countsEveryEntryThatIsNotCapturedAsExcluded() throws Exception {
        sitemapWith(SITEMAP, """
                https://example.org/a
                https://example.org/b
                https://example.org/b#duplicate
                https://example.org/c
                https://example.org/d
                https://other.example/x
                not a url
                """);

        SitemapUrls urls = reader.read(SITEMAP, 2);

        assertThat(urls.pageUrls()).containsExactly(URI.create("https://example.org/a"), URI.create("https://example.org/b"));
        assertThat(urls.listedUrlCount()).isEqualTo(6); // a, b, c, d, the other site and the invalid entry
        assertThat(urls.excludedUrlCount()).isEqualTo(4); // c and d beyond maxPages, the other site, the invalid entry
        assertThat(urls.pageUrls().size() + urls.excludedUrlCount()).isEqualTo(urls.listedUrlCount());
        assertThat(urls.origin()).isEqualTo(WebOrigin.of(SITEMAP));
    }

    @Test
    @DisplayName("Takes the site origin from where the sitemap was found after redirects")
    void takesTheOriginAfterRedirects() throws Exception {
        URI redirected = URI.create("https://www.example.org/sitemap.xml");
        sitemapWith(redirected, """
                https://www.example.org/a
                https://example.org/b
                """);

        SitemapUrls urls = reader.read(SITEMAP, 10);

        assertThat(urls.origin()).isEqualTo(WebOrigin.of(redirected));
        assertThat(urls.pageUrls()).containsExactly(URI.create("https://www.example.org/a"));
    }

    @Test
    @DisplayName("Refuses a .gz sitemap URL without downloading it")
    void refusesACompressedSitemapUrl() {
        assertThatThrownBy(() -> reader.read(URI.create("https://example.org/sitemap.XML.GZ"), 10))
                .isInstanceOfSatisfying(SitemapException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageStartingWith("Compressed (gzip) sitemaps are not supported");
        verifyNoInteractions(fetcher);
    }

    @ParameterizedTest
    @CsvSource({
            "application/xml,    true",
            "application/x-gzip, false"
    })
    @DisplayName("Refuses a downloaded sitemap that is gzip, by its first bytes or its Content-Type")
    void refusesACompressedSitemap(String contentType, boolean gzipBytes) throws Exception {
        byte[] body = gzipBytes ? GZIP_MAGIC : "<urlset/>".getBytes(StandardCharsets.UTF_8);
        serve(new FetchedResponse<>(SITEMAP, 200, contentType, body));

        assertThatThrownBy(() -> reader.read(SITEMAP, 10))
                .isInstanceOfSatisfying(SitemapException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageStartingWith("Compressed (gzip) sitemaps are not supported");
    }

    @ParameterizedTest
    @CsvSource({
            "503, true",
            "404, false"
    })
    @DisplayName("A failed download keeps whether it is worth retrying")
    void reportsAFailedDownload(int status, boolean retryable) throws Exception {
        serve(new FetchedResponse<>(SITEMAP, status, "text/html", null));

        assertThatThrownBy(() -> reader.read(SITEMAP, 10))
                .isInstanceOfSatisfying(SitemapException.class, e -> assertThat(e.isRetryable()).isEqualTo(retryable))
                .hasMessage("The sitemap could not be downloaded: HTTP status " + status);
    }

    @Test
    @DisplayName("Fails permanently when no listed page is on the sitemap's origin")
    void failsWithoutPagesOnTheOrigin() throws Exception {
        sitemapWith(SITEMAP, """
                https://other.example/x
                not a url
                """);

        assertThatThrownBy(() -> reader.read(SITEMAP, 10))
                .isInstanceOfSatisfying(SitemapException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessage("The sitemap lists no valid page URLs on its origin https://example.org (2 entries listed)");
    }

    /** Serves a sitemap listing {@code locations}, one per line, as found at {@code finalUrl}. */
    private void sitemapWith(URI finalUrl, String locations) throws Exception {
        StringBuilder xml = new StringBuilder("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        locations.lines().forEach(location -> xml.append("<url><loc>").append(location).append("</loc></url>"));
        byte[] body = xml.append("</urlset>").toString().getBytes(StandardCharsets.UTF_8);
        serve(new FetchedResponse<>(finalUrl, 200, "application/xml", body));
    }

    /** The sitemap is fetched without an origin restriction: its origin is only known afterwards. */
    private void serve(FetchedResponse<byte[]> response) throws Exception {
        when(fetcher.fetch(any(), anyLong(), isNull())).thenReturn(response);
    }
}
