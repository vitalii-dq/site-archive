package org.archive.sitearchive;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A web site on a local port, for the service to archive. Each path answers with what was registered for it, or
 * 404, and every request is counted. Requests are handled concurrently, so one that blocks does not hold up others.
 */
final class FakeSite implements AutoCloseable {

    /** Answers one request; may block, for example to keep a page in flight. */
    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    FakeSite() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
        server.start();
    }

    /** The site's origin, such as {@code http://127.0.0.1:54321}. */
    String origin() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    String url(String path) {
        return origin() + path;
    }

    FakeSite serve(String path, String contentType, String body) {
        return serve(path, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    FakeSite serve(String path, String contentType, byte[] body) {
        return handle(path, exchange -> respond(exchange, 200, contentType, body));
    }

    FakeSite html(String path, String body) {
        return serve(path, "text/html; charset=utf-8", body);
    }

    /** A {@code <urlset>} sitemap listing the URLs, which are used as given. */
    FakeSite sitemap(String path, String... urls) {
        String entries = Arrays.stream(urls).map(url -> "<url><loc>" + url + "</loc></url>")
                .collect(Collectors.joining());
        return serve(path, "application/xml",
                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" + entries + "</urlset>");
    }

    FakeSite handle(String path, Handler handler) {
        handlers.put(path, handler);
        return this;
    }

    int requests(String path) {
        AtomicInteger count = requests.get(path);
        return count == null ? 0 : count.get();
    }

    int totalRequests() {
        return requests.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = URI.create(exchange.getRequestURI().toString()).getPath();
            requests.computeIfAbsent(path, _ -> new AtomicInteger()).incrementAndGet();
            Handler handler = handlers.get(path);
            if (handler == null) {
                respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            } else {
                handler.handle(exchange);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
