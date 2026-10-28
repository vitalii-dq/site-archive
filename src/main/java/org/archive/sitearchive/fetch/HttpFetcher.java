package org.archive.sitearchive.fetch;

import org.archive.sitearchive.config.ArchiveProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Downloads a single URL within the configured limits. Redirects are followed here rather than by the
 * HTTP client so that, when given, every hop is checked against the required origin.
 * <p>
 * Destinations are not restricted: private, loopback and cloud metadata addresses can be fetched. This is
 * only acceptable while the service runs locally for a trusted user; see "Local use only" in the README.
 */
@Component
public class HttpFetcher {

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxRedirects;
    private final String userAgent;

    public HttpFetcher(HttpClient httpClient, ArchiveProperties properties) {
        this.httpClient = httpClient;
        this.requestTimeout = properties.limits().requestTimeout();
        this.maxRedirects = properties.limits().maxRedirects();
        this.userAgent = properties.fetch().userAgent();
    }

    /**
     * Downloads the body into memory. Only for bodies that are needed in memory anyway, such as the sitemap; use
     * {@link #fetchToFile} for anything that is stored.
     *
     * @param requiredOrigin origin every hop must stay within, or {@code null} for no restriction
     */
    public FetchedResponse<byte[]> fetch(URI url, long maxBodyBytes, WebOrigin requiredOrigin)
            throws FetchException, InterruptedException {
        return fetch(url, maxBodyBytes, requiredOrigin, BodySubscribers::ofByteArray);
    }

    /**
     * Streams the body into {@code file}, so that it is never held in memory. The file must not exist yet: use a new
     * one for every attempt, since a cancelled exchange may still be writing to the previous one. It is created only
     * for a successful response, and may be left behind, incomplete, when the download fails; deleting it is up to
     * the caller.
     *
     * @param requiredOrigin origin every hop must stay within, or {@code null} for no restriction
     */
    public FetchedResponse<Path> fetchToFile(URI url, long maxBodyBytes, WebOrigin requiredOrigin, Path file)
            throws FetchException, InterruptedException {
        return fetch(url, maxBodyBytes, requiredOrigin,
                () -> BodySubscribers.ofFile(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    private <B> FetchedResponse<B> fetch(URI url, long maxBodyBytes, WebOrigin requiredOrigin,
                                         Supplier<BodySubscriber<B>> successfulBody)
            throws FetchException, InterruptedException {
        URI current = url;
        for (int redirects = 0; ; redirects++) {
            if (requiredOrigin != null && !requiredOrigin.contains(current)) {
                throw FetchException.permanent(redirects == 0
                        ? "Outside the site origin " + requiredOrigin + ": " + current
                        : "Redirected outside the site origin " + requiredOrigin + " to " + current);
            }
            HttpResponse<B> response = send(current, maxBodyBytes, successfulBody);
            if (!isRedirect(response.statusCode())) {
                String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null);
                return new FetchedResponse<>(current, response.statusCode(), contentType, response.body());
            }
            if (redirects == maxRedirects) {
                throw FetchException.permanent("Too many redirects (limit is " + maxRedirects + ")");
            }
            current = redirectTarget(current, response);
        }
    }

    private <B> HttpResponse<B> send(URI url, long maxBodyBytes, Supplier<BodySubscriber<B>> successfulBody)
            throws FetchException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET()
                .build();
        CompletableFuture<HttpResponse<B>> exchange =
                httpClient.sendAsync(request, info -> bodySubscriber(info, maxBodyBytes, successfulBody));
        try {
            return exchange.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw timedOut(e);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw translate(e.getCause(), maxBodyBytes);
        }
    }

    /**
     * Only successful bodies are needed; redirect and error bodies are discarded unread. A body whose declared
     * length is over the limit is refused before any of it is read. A body without a declared length is cut off
     * by the JDK once it passes the limit; that failure is an ordinary {@link IOException}, so it is retried.
     */
    private static <B> BodySubscriber<B> bodySubscriber(ResponseInfo info, long maxBodyBytes,
                                                       Supplier<BodySubscriber<B>> successfulBody) {
        if (!FetchedResponse.isSuccessful(info.statusCode())) {
            return BodySubscribers.replacing(null);
        }
        if (info.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH).orElse(-1) > maxBodyBytes) {
            throw new ResponseTooLargeException();
        }
        return BodySubscribers.limiting(successfulBody.get(), maxBodyBytes);
    }

    /** The client's own request timeout and the wait for the exchange use the same limit, so either may fire. */
    private FetchException timedOut(Exception cause) {
        long millis = requestTimeout.toMillis();
        String limit = millis % 1000 == 0 ? millis / 1000 + "s" : millis + "ms";
        return FetchException.retryable("Request timed out after " + limit, cause);
    }

    private FetchException translate(Throwable failure, long maxBodyBytes) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return switch (cause) {
            case ResponseTooLargeException _ ->
                    FetchException.permanent("Response body exceeds the limit of " + maxBodyBytes + " bytes");
            case HttpTimeoutException timeout -> timedOut(timeout);
            case IOException io -> FetchException.retryable("Network error: " + describe(io), io);
            default -> FetchException.permanent("Request failed: " + describe(cause));
        };
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static URI redirectTarget(URI current, HttpResponse<?> response) throws FetchException {
        String location = response.headers().firstValue(HttpHeaders.LOCATION)
                .orElseThrow(() -> FetchException.permanent("Redirect without a Location header"));
        URI target;
        try {
            target = HttpUrls.resolve(current, URI.create(location.strip()));
        } catch (IllegalArgumentException e) {
            throw FetchException.permanent("Redirect to an invalid location: " + location);
        }
        return HttpUrls.parse(target.toString())
                .orElseThrow(() -> FetchException.permanent("Redirect to an unsupported location: " + location));
    }

    private static final class ResponseTooLargeException extends RuntimeException {
    }
}
