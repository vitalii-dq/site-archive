package org.archive.sitearchive.fetch;

import org.archive.sitearchive.config.ArchiveProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
     * @param requiredOrigin origin every hop must stay within, or {@code null} for no restriction
     */
    public FetchedResponse fetch(URI url, long maxBodyBytes, WebOrigin requiredOrigin)
            throws FetchException, InterruptedException {
        URI current = url;
        for (int redirects = 0; ; redirects++) {
            if (requiredOrigin != null && !requiredOrigin.contains(current)) {
                throw FetchException.permanent("Redirected outside the site origin " + requiredOrigin + " to " + current);
            }
            HttpResponse<byte[]> response = send(current, maxBodyBytes);
            if (!isRedirect(response.statusCode())) {
                String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null);
                return new FetchedResponse(current, response.statusCode(), contentType, response.body());
            }
            if (redirects == maxRedirects) {
                throw FetchException.permanent("Too many redirects (limit is " + maxRedirects + ")");
            }
            current = redirectTarget(current, response);
        }
    }

    private HttpResponse<byte[]> send(URI url, long maxBodyBytes) throws FetchException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> exchange =
                httpClient.sendAsync(request, info -> bodySubscriber(info, maxBodyBytes));
        try {
            return exchange.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw FetchException.retryable("Request timed out after " + requestTimeout.toSeconds() + "s");
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
    private static HttpResponse.BodySubscriber<byte[]> bodySubscriber(ResponseInfo info, long maxBodyBytes) {
        if (!FetchedResponse.isSuccessful(info.statusCode())) {
            return BodySubscribers.replacing(new byte[0]);
        }
        if (info.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH).orElse(-1) > maxBodyBytes) {
            throw new ResponseTooLargeException();
        }
        return BodySubscribers.limiting(BodySubscribers.ofByteArray(), maxBodyBytes);
    }

    private static FetchException translate(Throwable failure, long maxBodyBytes) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return switch (cause) {
            case ResponseTooLargeException _ ->
                    FetchException.permanent("Response body exceeds the limit of " + maxBodyBytes + " bytes");
            case HttpTimeoutException timeout -> FetchException.retryable("Request timed out", timeout);
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
            target = current.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw FetchException.permanent("Redirect to an invalid location: " + location);
        }
        return HttpUrls.parse(target.toString())
                .orElseThrow(() -> FetchException.permanent("Redirect to an unsupported location: " + location));
    }

    private static final class ResponseTooLargeException extends RuntimeException {
    }
}
