package org.archive.sitearchive.fetch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.archive.sitearchive.config.ArchiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpFetcherTest {

    private static final long MAX_BYTES = 100;
    private static final int MAX_REDIRECTS = 2;
    private static final String USER_AGENT = "site-archive-test";

    @TempDir
    private Path directory;

    private HttpServer server;
    private HttpFetcher fetcher;
    private WebOrigin origin;
    private final AtomicInteger requests = new AtomicInteger();
    /** {@code /slow} answers only once this is released, and counts down {@link #slowRequested} on arrival. */
    private final CountDownLatch slowReleased = new CountDownLatch(1);
    private final CountDownLatch slowRequested = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", exchange -> respond(exchange, 200, "<p>hello</p>"));
        server.createContext("/moved", exchange -> redirect(exchange, "/page"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
        server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat((int) MAX_BYTES + 1)));
        server.createContext("/hops", exchange -> {
            requests.incrementAndGet();
            int remaining = Integer.parseInt(exchange.getRequestURI().getQuery());
            if (remaining == 0) {
                respond(exchange, 200, "arrived");
            } else {
                redirect(exchange, "/hops?" + (remaining - 1));
            }
        });
        server.createContext("/slow", exchange -> {
            slowRequested.countDown();
            try {
                slowReleased.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        server.start();
        origin = WebOrigin.of(url("/"));
        fetcher = fetcher(Duration.ofSeconds(5));
    }

    @AfterEach
    void stopServer() {
        slowReleased.countDown();
        server.stop(0);
    }

    @Test
    @DisplayName("Follows redirects and streams the body of the final response into the file")
    void streamsTheBodyOfTheFinalResponseIntoTheFile() throws Exception {
        Path file = directory.resolve("body");

        FetchedResponse<Path> response = fetcher.fetchToFile(url("/moved"), MAX_BYTES, origin, file);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.finalUrl()).isEqualTo(url("/page"));
        assertThat(response.contentType()).isEqualTo("text/html");
        assertThat(response.body()).isEqualTo(file);
        assertThat(file).hasContent("<p>hello</p>");
    }

    @Test
    @DisplayName("Returns an error response without reading its body or creating the file")
    void doesNotCreateTheFileForAnErrorResponse() throws Exception {
        Path file = directory.resolve("body");

        FetchedResponse<Path> response = fetcher.fetchToFile(url("/missing"), MAX_BYTES, origin, file);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isNull();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("Refuses a declared length over the limit, permanently and without reading the body")
    void refusesADeclaredLengthOverTheLimitWithoutReadingTheBody() {
        Path file = directory.resolve("body");

        assertThatThrownBy(() -> fetcher.fetchToFile(url("/large"), MAX_BYTES, origin, file))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessageContaining("exceeds the limit");
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("Cuts off a body without a declared length once it passes the limit, as a retryable failure")
    void cutsOffAnUndeclaredLengthOverTheLimit() {
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[(int) MAX_BYTES + 1]);
            }
        });

        assertThatThrownBy(() -> fetcher.fetchToFile(url("/chunked"), MAX_BYTES, origin, directory.resolve("body")))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    @DisplayName("Reads the body into memory when asked")
    void stillReadsIntoMemoryWhenAsked() throws Exception {
        FetchedResponse<byte[]> response = fetcher.fetch(url("/page"), MAX_BYTES, null);

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("<p>hello</p>");
    }

    @Test
    @DisplayName("Sends the configured User-Agent")
    void sendsTheUserAgent() throws Exception {
        server.createContext("/agent", exchange ->
                respond(exchange, 200, exchange.getRequestHeaders().getFirst("User-Agent")));

        FetchedResponse<byte[]> response = fetcher.fetch(url("/agent"), MAX_BYTES, null);

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(USER_AGENT);
    }

    @Test
    @DisplayName("Follows as many redirects as the limit allows")
    void followsRedirectsUpToTheLimit() throws Exception {
        FetchedResponse<byte[]> response = fetcher.fetch(url("/hops?" + MAX_REDIRECTS), MAX_BYTES, origin);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Resolves a redirect to a query against the current path, as RFC 3986 does")
    void resolvesARedirectToAQueryAgainstTheCurrentPath() throws Exception {
        server.createContext("/list", exchange -> {
            if (exchange.getRequestURI().getQuery() == null) {
                redirect(exchange, "?page=2");
            } else {
                respond(exchange, 200, "page 2");
            }
        });

        FetchedResponse<byte[]> response = fetcher.fetch(url("/list"), MAX_BYTES, origin);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.finalUrl()).isEqualTo(url("/list?page=2"));
    }

    @Test
    @DisplayName("Fails permanently on one redirect more than the limit, without following it")
    void refusesMoreRedirectsThanTheLimit() {
        assertThatThrownBy(() -> fetcher.fetch(url("/hops?" + (MAX_REDIRECTS + 1)), MAX_BYTES, origin))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessage("Too many redirects (limit is " + MAX_REDIRECTS + ")");
        assertThat(requests).hasValue(MAX_REDIRECTS + 1);
    }

    @Test
    @DisplayName("Refuses a URL outside the required origin without requesting it")
    void refusesAUrlOutsideTheOrigin() {
        URI elsewhere = URI.create("http://localhost:" + server.getAddress().getPort() + "/page");

        assertThatThrownBy(() -> fetcher.fetch(elsewhere, MAX_BYTES, origin))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessage("Outside the site origin " + origin + ": " + elsewhere);
    }

    @Test
    @DisplayName("Does not follow a redirect outside the required origin")
    void refusesARedirectOutsideTheOrigin() {
        URI elsewhere = URI.create("http://localhost:" + server.getAddress().getPort() + "/page");
        server.createContext("/away", exchange -> redirect(exchange, elsewhere.toString()));

        assertThatThrownBy(() -> fetcher.fetch(url("/away"), MAX_BYTES, origin))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessage("Redirected outside the site origin " + origin + " to " + elsewhere);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NONE,                Redirect without a Location header",
            "http://exa mple.org/, Redirect to an invalid location: http://exa mple.org/",
            "ftp://example.org/,  Redirect to an unsupported location: ftp://example.org/"
    }, nullValues = "NONE")
    @DisplayName("Fails permanently on a redirect without a usable Location")
    void refusesARedirectWithoutAUsableLocation(String location, String message) {
        server.createContext("/bad-redirect", exchange -> redirect(exchange, location));

        assertThatThrownBy(() -> fetcher.fetch(url("/bad-redirect"), MAX_BYTES, origin))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isFalse())
                .hasMessage(message);
    }

    @ParameterizedTest
    @CsvSource({
            "200,  Request timed out after 200ms",
            "1000, Request timed out after 1s"
    })
    @DisplayName("A request that takes longer than the request timeout fails as retryable, naming the timeout")
    void timesOut(long timeoutMillis, String message) {
        HttpFetcher impatient = fetcher(Duration.ofMillis(timeoutMillis));

        assertThatThrownBy(() -> impatient.fetch(url("/slow"), MAX_BYTES, origin))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isTrue())
                .hasMessage(message);
    }

    @Test
    @DisplayName("A network error fails as retryable")
    void treatsNetworkErrorsAsRetryable() {
        server.createContext("/hang-up", HttpExchange::close);

        assertThatThrownBy(() -> fetcher.fetch(url("/hang-up"), MAX_BYTES, null))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isRetryable()).isTrue())
                .hasMessageStartingWith("Network error");
    }

    @Test
    @DisplayName("An interrupt while waiting for the response is passed on to the caller")
    void passesOnAnInterrupt() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                fetcher.fetch(url("/slow"), MAX_BYTES, origin);
                failure.complete(null);
            } catch (Throwable e) {
                failure.complete(e);
            }
        });
        assertThat(slowRequested.await(5, TimeUnit.SECONDS)).isTrue();

        caller.interrupt();

        assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
    }

    private HttpFetcher fetcher(Duration requestTimeout) {
        return new HttpFetcher(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new ArchiveProperties(null, new ArchiveProperties.Limits(1, 1, DataSize.ofBytes(MAX_BYTES),
                        DataSize.ofBytes(MAX_BYTES), DataSize.ofBytes(MAX_BYTES), 1, MAX_REDIRECTS,
                        Duration.ofSeconds(5), requestTimeout), new ArchiveProperties.Fetch(USER_AGENT), null, null));
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** A 302 to {@code location}, or without a Location header if it is {@code null}. */
    private static void redirect(HttpExchange exchange, String location) throws IOException {
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        respond(exchange, 302, "moved");
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
