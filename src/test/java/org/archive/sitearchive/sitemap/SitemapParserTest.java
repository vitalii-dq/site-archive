package org.archive.sitearchive.sitemap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapParserTest {

    private final SitemapParser parser = new SitemapParser();

    @Test
    @DisplayName("Reads <loc> of <url> entries in document order, unescaped and stripped, ignoring <loc> anywhere else")
    void readsTheLocationsOfUrlEntries() throws SitemapException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
                        xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"
                        xmlns:xhtml="http://www.w3.org/1999/xhtml">
                  <loc>https://example.org/not-in-an-entry</loc>
                  <other><loc>https://example.org/in-another-entry</loc></other>
                  <url><image:loc>https://example.org/other-namespace.jpg</image:loc></url>
                  <url>
                    <loc>
                      https://example.org/a?x=1&amp;y=2
                    </loc>
                    <lastmod>2026-10-01</lastmod>
                    <image:image><image:loc>https://example.org/photo.jpg</image:loc></image:image>
                    <xhtml:link rel="alternate" hreflang="de" href="https://example.org/de/a"/>
                  </url>
                  <url><nested><loc>https://example.org/too-deep</loc></nested></url>
                  <url><loc>https://example.org/b</loc></url>
                </urlset>
                """;

        assertThat(parser.parseLocations(bytes(xml)))
                .containsExactly("https://example.org/a?x=1&y=2", "https://example.org/b");
    }

    @Test
    @DisplayName("Accepts a sitemap without any namespace")
    void acceptsASitemapWithoutNamespace() throws SitemapException {
        String xml = "<urlset><url><loc>https://example.org/a</loc></url></urlset>";

        assertThat(parser.parseLocations(bytes(xml))).containsExactly("https://example.org/a");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"/> | The URL points to a sitemap index",
            "<html><body>Not found</body></html>                                  | The document is not a sitemap",
            "<urlset xmlns=\"http://example.org/other\"/>                          | The document is not a sitemap",
            "<urlset><url><loc>https://example.org/a</loc></url>                  | The sitemap is not valid XML"
    })
    @DisplayName("Rejects, permanently, a sitemap index, another document and malformed XML")
    void rejectsAnythingButAUrlset(String xml, String message) {
        assertThatThrownBy(() -> parser.parseLocations(bytes(xml)))
                .isInstanceOfSatisfying(SitemapException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageStartingWith(message);
    }

    @Test
    @DisplayName("Rejects a DTD without loading it, so a sitemap cannot make the service request other URLs")
    void rejectsADtdWithoutLoadingIt() throws IOException {
        AtomicInteger dtdRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/sitemap.dtd", exchange -> {
            dtdRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String xml = "<!DOCTYPE urlset SYSTEM \"http://127.0.0.1:" + server.getAddress().getPort() + "/sitemap.dtd\">"
                    + "<urlset><url><loc>https://example.org/a</loc></url></urlset>";

            assertThatThrownBy(() -> parser.parseLocations(bytes(xml)))
                    .isInstanceOfSatisfying(SitemapException.class, e -> assertThat(e.isRetryable()).isFalse())
                    .hasMessageStartingWith("The sitemap is not valid XML");
            assertThat(dtdRequests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
