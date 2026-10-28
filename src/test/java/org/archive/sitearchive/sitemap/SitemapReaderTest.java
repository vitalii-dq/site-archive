package org.archive.sitearchive.sitemap;

import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SitemapReaderTest {

    private static final URI SITEMAP = URI.create("https://example.org/sitemap.xml");

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final SitemapReader reader = new SitemapReader(fetcher, new SitemapParser(), new ArchiveProperties(null,
            new ArchiveProperties.Limits(100, 1000, DataSize.ofMegabytes(10), DataSize.ofMegabytes(5),
                    DataSize.ofMegabytes(10), 2000, 5, Duration.ofSeconds(5), Duration.ofSeconds(20)),
            null, null, null));

    @Test
    void countsEveryEntryThatIsNotCapturedAsExcluded() throws Exception {
        sitemapWith("""
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

    private void sitemapWith(String locations) throws Exception {
        StringBuilder xml = new StringBuilder("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        locations.lines().forEach(location -> xml.append("<url><loc>").append(location).append("</loc></url>"));
        byte[] body = xml.append("</urlset>").toString().getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch(any(), anyLong(), isNull()))
                .thenReturn(new FetchedResponse(SITEMAP, 200, "application/xml", body));
    }
}
