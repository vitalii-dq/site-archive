package org.archive.sitearchive.fetch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.archive.sitearchive.config.ArchiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpFetcherTest {

    private static final long MAX_BYTES = 100;

    @TempDir
    private Path directory;

    private HttpServer server;
    private HttpFetcher fetcher;
    private WebOrigin origin;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", exchange -> respond(exchange, 200, "<p>hello</p>"));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            respond(exchange, 302, "moved");
        });
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat((int) MAX_BYTES + 1)));
        server.start();
        origin = WebOrigin.of(url("/"));
        fetcher = new HttpFetcher(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new ArchiveProperties(null, new ArchiveProperties.Limits(1, 1, DataSize.ofBytes(MAX_BYTES),
                        DataSize.ofBytes(MAX_BYTES), DataSize.ofBytes(MAX_BYTES), 1, 5, Duration.ofSeconds(5),
                        Duration.ofSeconds(5)), new ArchiveProperties.Fetch("test"), null, null));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void streamsTheBodyOfTheFinalResponseIntoTheFile() throws Exception {
        Path file = directory.resolve("body");

        FetchedResponse<Path> response = fetcher.fetchToFile(url("/moved"), MAX_BYTES, origin, file);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.finalUrl()).isEqualTo(url("/page"));
        assertThat(response.body()).isEqualTo(file);
        assertThat(file).hasContent("<p>hello</p>");
    }

    @Test
    void doesNotCreateTheFileForAnErrorResponse() throws Exception {
        Path file = directory.resolve("body");

        FetchedResponse<Path> response = fetcher.fetchToFile(url("/missing"), MAX_BYTES, origin, file);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isNull();
        assertThat(file).doesNotExist();
    }

    @Test
    void refusesADeclaredLengthOverTheLimitWithoutReadingTheBody() {
        Path file = directory.resolve("body");

        assertThatThrownBy(() -> fetcher.fetchToFile(url("/large"), MAX_BYTES, origin, file))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageContaining("exceeds the limit");
        assertThat(file).doesNotExist();
    }

    @Test
    void stillReadsIntoMemoryWhenAsked() throws Exception {
        FetchedResponse<byte[]> response = fetcher.fetch(url("/page"), MAX_BYTES, null);

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("<p>hello</p>");
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
