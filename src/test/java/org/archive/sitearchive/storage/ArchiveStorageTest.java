package org.archive.sitearchive.storage;

import org.archive.sitearchive.config.TestArchiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveStorageTest {

    private static final String JOB_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID JOB = UUID.fromString(JOB_ID);
    private static final String PAGE_HTML = "<html><head><link rel=\"stylesheet\" href=\"site.css\"></head>"
            + "<body><img src=\"missing.png\"></body></html>";
    private static final String CSS = "body{background:url(bg.png)}";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff};

    @TempDir
    private Path root;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private ArchiveStorage storage;

    @BeforeEach
    void createStorage() {
        storage = new ArchiveStorage(TestArchiveProperties.builder().storageRoot(root).build(),
                jsonMapper);
        storage.prepareJobDirectory(JOB);
    }

    @Test
    @DisplayName("Packages the index, the manifest and only the captured pages and assets into the ZIP")
    void packagesTheCapturedFiles() throws IOException {
        ArchiveManifest manifest = captureOnePage();

        StoredArchive archive = storage.publishArchive(JOB, manifest);

        assertThat(archive.relativePath()).isEqualTo(JOB + "/archive.zip");
        assertThat(archive.sizeBytes()).isEqualTo(Files.size(root.resolve(archive.relativePath())));
        Map<String, byte[]> zip = unzip(archive);
        assertThat(zip).containsOnlyKeys("index.html", "manifest.json", "pages/1-old-post.html",
                "assets/1-blog-site.css", "assets/2-blog-bg.png");
        assertThat(jsonMapper.readValue(zip.get("manifest.json"), ArchiveManifest.class)).isEqualTo(manifest);
        assertThat(zip.get("assets/2-blog-bg.png")).isEqualTo(PNG);
    }

    @Test
    @DisplayName("Points references in the ZIP at the copies relative to each file, resolving against the page's "
            + "final URL, and keeps the stored files as served")
    void rewritesReferencesInTheZipOnly() throws IOException {
        Map<String, byte[]> zip = unzip(storage.publishArchive(JOB, captureOnePage()));

        assertThat(new String(zip.get("pages/1-old-post.html"), StandardCharsets.UTF_8))
                .contains("<link rel=\"stylesheet\" href=\"../assets/1-blog-site.css\">")
                .contains("<img src=\"https://example.org/blog/missing.png\">");
        assertThat(zip.get("assets/1-blog-site.css")).asString(StandardCharsets.UTF_8)
                .isEqualTo("body{background:url(\"2-blog-bg.png\")}");
        assertThat(storage.readFile(JOB, "pages/1-old-post.html")).asString(StandardCharsets.UTF_8).isEqualTo(PAGE_HTML);
        assertThat(storage.readFile(JOB, "assets/1-blog-site.css")).asString(StandardCharsets.UTF_8).isEqualTo(CSS);
    }

    @Test
    @DisplayName("A failure while packaging keeps the previous ZIP and leaves nothing staged")
    void keepsThePreviousArchiveOnFailure() throws IOException {
        ArchiveManifest manifest = captureOnePage();
        Path zip = root.resolve(storage.publishArchive(JOB, manifest).relativePath());
        byte[] previous = Files.readAllBytes(zip);
        ArchiveManifest broken = manifest(List.of(page("https://example.org/gone", "pages/9-gone.html",
                "https://example.org/gone")), List.of());

        assertThatThrownBy(() -> storage.publishArchive(JOB, broken)).isInstanceOf(UncheckedIOException.class);

        assertThat(zip).hasBinaryContent(previous);
        assertThat(root.resolve(JOB + "/.staging")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Preparing the job directory again discards staged files and assets but keeps captured pages")
    void preparesTheJobDirectoryForAnotherRun() throws IOException {
        String page = storage.publishPage(JOB, 1, URI.create("https://example.org/a"), staged("<p>a</p>")).fileName();
        String asset = storage.publishAsset(JOB, 1, URI.create("https://example.org/a.png"), "image/png", staged("png"))
                .fileName();
        Path leftOver = staged("incomplete");

        storage.prepareJobDirectory(JOB);

        assertThat(root.resolve(JOB.toString()).resolve(page)).exists();
        assertThat(root.resolve(JOB.toString()).resolve(asset)).doesNotExist();
        assertThat(leftOver).doesNotExist();
        assertThat(root.resolve(JOB + "/assets")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Deleting the captured files of a finished job keeps only the ZIP")
    void deletesTheCapturedFilesButKeepsTheArchive() throws IOException {
        StoredArchive archive = storage.publishArchive(JOB, captureOnePage());
        byte[] zip = Files.readAllBytes(root.resolve(archive.relativePath()));
        staged("incomplete");

        storage.deleteCapturedFiles(JOB);

        try (var files = Files.list(root.resolve(JOB.toString()))) {
            assertThat(files).containsExactly(root.resolve(archive.relativePath()));
        }
        assertThat(root.resolve(archive.relativePath())).hasBinaryContent(zip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.zip", JOB_ID + "/../../outside.zip", "/etc/passwd"})
    @DisplayName("Refuses to resolve a stored path outside the storage root")
    void refusesPathsOutsideTheRoot(String relativePath) {
        assertThatIllegalArgumentException().isThrownBy(() -> storage.resolve(relativePath));
    }

    /**
     * One page, found at {@code /blog/post.html} after a redirect, using a stylesheet that uses an image, and an
     * image that could not be captured; plus a page that could not be captured.
     */
    private ArchiveManifest captureOnePage() throws IOException {
        String page = storage.publishPage(JOB, 1, URI.create("https://example.org/old/post.html"), staged(PAGE_HTML))
                .fileName();
        String css = storage.publishAsset(JOB, 1, URI.create("https://example.org/blog/site.css"), "text/css",
                staged(CSS)).fileName();
        Path png = storage.newStagingFile(JOB);
        Files.write(png, PNG);
        String image = storage.publishAsset(JOB, 2, URI.create("https://example.org/blog/bg.png"), "image/png", png)
                .fileName();
        return manifest(
                List.of(page("https://example.org/old/post.html", page, "https://example.org/blog/post.html"),
                        new ArchiveManifest.Entry("https://example.org/broken.html", "FAILED", null, 500, null, null, 3,
                                "HTTP status 500")),
                List.of(asset("https://example.org/blog/site.css", css, "text/css"),
                        asset("https://example.org/blog/bg.png", image, "image/png"),
                        new ArchiveManifest.Asset("https://example.org/blog/missing.png", null, null, null, "HTTP status 404")));
    }

    private static ArchiveManifest manifest(List<ArchiveManifest.Entry> pages, List<ArchiveManifest.Asset> assets) {
        return new ArchiveManifest(JOB, "https://example.org/sitemap.xml", "https://example.org",
                Instant.parse("2026-10-28T10:00:00Z"), ArchiveManifest.SNAPSHOT_NOTICE, pages, assets, 0);
    }

    private static ArchiveManifest.Entry page(String sourceUrl, String file, String finalUrl) {
        return new ArchiveManifest.Entry(sourceUrl, "CAPTURED", file, 200, "text/html; charset=utf-8", finalUrl, 1, null);
    }

    private static ArchiveManifest.Asset asset(String sourceUrl, String file, String contentType) {
        return new ArchiveManifest.Asset(sourceUrl, file, sourceUrl, contentType, null);
    }

    private Path staged(String content) throws IOException {
        Path file = storage.newStagingFile(JOB);
        Files.writeString(file, content);
        return file;
    }

    private Map<String, byte[]> unzip(StoredArchive archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(root.resolve(archive.relativePath()));
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
