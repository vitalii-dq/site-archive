package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.PageResult;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.archive.sitearchive.storage.ArchiveManifest;
import org.archive.sitearchive.storage.ArchiveStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetCapturerTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final WebOrigin SITE = WebOrigin.parse("https://example.org");
    private static final int MAX_ASSETS = 2;

    /** A response the fake site serves; a {@code null} body means the download fails half way. */
    private record Served(String contentType, byte[] body) {
    }

    @TempDir
    private Path root;

    private final HttpFetcher fetcher = mock(HttpFetcher.class);
    private final Map<URI, Served> site = new HashMap<>();
    private ArchiveStorage storage;
    private AssetCapturer capturer;

    @BeforeEach
    void setUp() {
        storage = new ArchiveStorage(new ArchiveProperties(new ArchiveProperties.Storage(root), null, null, null, null),
                JsonMapper.builder().build());
        storage.prepareJobDirectory(JOB);
        capturer = new AssetCapturer(fetcher, storage,
                new Retrier(new ArchiveProperties(null, null, null,
                        new ArchiveProperties.Retry(1, Duration.ofMillis(1), 2.0, Duration.ofMillis(1)), null)),
                new ArchiveProperties(null, new ArchiveProperties.Limits(1, 1, DataSize.ofMegabytes(1),
                        DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), MAX_ASSETS, 0, Duration.ofSeconds(1),
                        Duration.ofSeconds(1)), null, null, null));
    }

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

    @Test
    void storesEachAssetAsDownloadedAndLeavesNothingStaged() throws Exception {
        serve("/site.css", "text/css", "body { color: red }");

        AssetCapturer.Result result = captureAll(List.of(page(1, "<link rel=\"stylesheet\" href=\"/site.css\">")));

        ArchiveManifest.Asset asset = result.assets().getFirst();
        assertThat(asset.file()).isEqualTo("assets/1-site.css");
        assertThat(storage.readFile(JOB, asset.file())).asString(StandardCharsets.UTF_8).isEqualTo("body { color: red }");
        assertThat(staged()).isEmpty();
    }

    @Test
    void discardsAFailedDownload() throws Exception {
        site.put(URI.create("https://example.org/a.png"), new Served("image/png", null));

        AssetCapturer.Result result = captureAll(List.of(page(1, "<img src=\"/a.png\">")));

        assertThat(result.assets()).singleElement().satisfies(asset -> {
            assertThat(asset.captured()).isFalse();
            assertThat(asset.error()).isEqualTo("Network error: connection reset");
        });
        assertThat(staged()).isEmpty();
    }

    /** Writes the body into the file the way the real fetcher does: the file appears only once there is a body. */
    private AssetCapturer.Result captureAll(List<PageResult> pages) throws Exception {
        when(fetcher.fetchToFile(any(), anyLong(), eq(SITE), any())).thenAnswer(invocation -> {
            URI url = invocation.getArgument(0);
            Path file = invocation.getArgument(3);
            Served served = site.getOrDefault(url, new Served("image/png", new byte[0]));
            if (served.body() == null) {
                Files.write(file, new byte[]{1, 2, 3}, StandardOpenOption.CREATE_NEW);
                throw FetchException.retryable("Network error: connection reset");
            }
            Files.write(file, served.body(), StandardOpenOption.CREATE_NEW);
            return new FetchedResponse<>(url, 200, served.contentType(), file);
        });
        return capturer.captureAll(JOB, SITE, pages);
    }

    private List<Path> staged() throws IOException {
        try (Stream<Path> files = Files.list(root.resolve(JOB.toString()).resolve(".staging"))) {
            return files.toList();
        }
    }

    private static List<String> sourceUrls(AssetCapturer.Result result) {
        return result.assets().stream().map(ArchiveManifest.Asset::sourceUrl).toList();
    }

    private PageResult page(int id, String html) throws IOException {
        String file = "pages/" + id + ".html";
        Files.writeString(root.resolve(JOB.toString()).resolve(file), html);
        return new PageResult("https://example.org/" + id + ".html", PageStatus.SUCCEEDED, file, 200,
                "text/html", "https://example.org/" + id + ".html", 1, null);
    }

    private void serve(String path, String contentType, String body) {
        site.put(URI.create("https://example.org" + path), new Served(contentType, body.getBytes(StandardCharsets.UTF_8)));
    }
}
