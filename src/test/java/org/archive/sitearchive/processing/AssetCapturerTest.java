package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.PageResult;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.archive.sitearchive.storage.ArchiveManifest;
import org.archive.sitearchive.storage.ArchiveStorage;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetCapturerTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final WebOrigin SITE = WebOrigin.parse("https://example.org");
    private static final int MAX_ASSETS = 2;

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final ArchiveStorage storage = mock(ArchiveStorage.class);
    private final Map<URI, FetchedResponse> site = new HashMap<>();
    private final AssetCapturer capturer = new AssetCapturer(fetcher, storage,
            new Retrier(new ArchiveProperties(null, null, null,
                    new ArchiveProperties.Retry(1, Duration.ofMillis(1), 2.0, Duration.ofMillis(1)), null)),
            new ArchiveProperties(null, new ArchiveProperties.Limits(1, 1, DataSize.ofMegabytes(1),
                    DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), MAX_ASSETS, 0, Duration.ofSeconds(1),
                    Duration.ofSeconds(1)), null, null, null));

    @Test
    void capturesStylingBeforeImagesWhenTheLimitIsReached() throws Exception {
        List<PageResult> pages = List.of(
                page(1, "<img src=\"/a.png\"><img src=\"/b.png\">"),
                page(2, "<link rel=\"stylesheet\" href=\"/site.css\">"));
        serve("/site.css", "text/css", "@font-face { src: url(/font.woff2) }");

        AssetCapturer.Result result = captureAll(pages);

        assertThat(sourceUrls(result)).containsExactly("https://example.org/site.css", "https://example.org/font.woff2");
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test
    void skipsNothingBelowTheLimit() throws Exception {
        AssetCapturer.Result result = captureAll(List.of(page(1, "<img src=\"/a.png\">")));

        assertThat(sourceUrls(result)).containsExactly("https://example.org/a.png");
        assertThat(result.skipped()).isZero();
    }

    @Test
    void movesAnAssetUpWhenAStylesheetTurnsOutToUseIt() throws Exception {
        List<PageResult> pages = List.of(
                page(1, "<img src=\"/a.png\">"),
                page(2, "<link rel=\"preload\" as=\"font\" href=\"/font.woff2\"><link rel=\"stylesheet\" href=\"/site.css\">"));
        serve("/site.css", "text/css", "@font-face { src: url(/font.woff2) }");

        assertThat(sourceUrls(captureAll(pages))).containsExactly(
                "https://example.org/site.css", "https://example.org/font.woff2");
    }

    private AssetCapturer.Result captureAll(List<PageResult> pages) throws Exception {
        when(fetcher.fetch(any(), anyLong(), eq(SITE))).thenAnswer(invocation -> site.getOrDefault(
                invocation.<URI>getArgument(0), new FetchedResponse(invocation.getArgument(0), 200, "image/png", new byte[0])));
        when(storage.storeAsset(eq(JOB), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> "assets/" + invocation.getArgument(1));
        return capturer.captureAll(JOB, SITE, pages);
    }

    private static List<String> sourceUrls(AssetCapturer.Result result) {
        return result.assets().stream().map(ArchiveManifest.Asset::sourceUrl).toList();
    }

    private PageResult page(int id, String html) {
        String file = "pages/" + id + ".html";
        when(storage.readPage(JOB, file)).thenReturn(html.getBytes(StandardCharsets.UTF_8));
        return new PageResult("https://example.org/" + id + ".html", PageStatus.SUCCEEDED, file, 200,
                "text/html", "https://example.org/" + id + ".html", 1, null);
    }

    private void serve(String path, String contentType, String body) {
        URI url = URI.create("https://example.org" + path);
        site.put(url, new FetchedResponse(url, 200, contentType, body.getBytes(StandardCharsets.UTF_8)));
    }
}
