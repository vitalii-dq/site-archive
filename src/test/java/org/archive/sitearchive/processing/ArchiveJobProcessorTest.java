package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.ArchiveJobLifecycle;
import org.archive.sitearchive.archive.JobSnapshot;
import org.archive.sitearchive.archive.PageResult;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.archive.PendingPage;
import org.archive.sitearchive.config.TestArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.WebOrigin;
import org.archive.sitearchive.sitemap.SitemapException;
import org.archive.sitearchive.sitemap.SitemapReader;
import org.archive.sitearchive.sitemap.SitemapUrls;
import org.archive.sitearchive.storage.ArchiveManifest;
import org.archive.sitearchive.storage.ArchiveStorage;
import org.archive.sitearchive.storage.StoredArchive;
import org.archive.sitearchive.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArchiveJobProcessorTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final URI SITEMAP = URI.create("https://example.org/sitemap.xml");
    private static final WebOrigin SITE = WebOrigin.parse("https://example.org");
    private static final int MAX_PAGES = 10;
    private static final int MAX_ATTEMPTS = 3;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Path STAGED = Path.of("staged.tmp");
    private static final StoredArchive ARCHIVE = new StoredArchive(JOB + "/archive.zip", 100);

    private final ArchiveJobLifecycle lifecycle = mock(ArchiveJobLifecycle.class);
    private final SitemapReader sitemapReader = mock(SitemapReader.class);
    private final PageCapturer pageCapturer = mock(PageCapturer.class);
    private final AssetCapturer assetCapturer = mock(AssetCapturer.class);
    private final ArchiveStorage storage = mock(ArchiveStorage.class);
    private final ArchiveJobProcessor processor = new ArchiveJobProcessor(lifecycle, sitemapReader, pageCapturer,
            assetCapturer, storage,
            new Retrier(TestArchiveProperties.builder().maxAttempts(MAX_ATTEMPTS).build()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(storage.newStagingFile(JOB)).thenReturn(STAGED);
        when(storage.publishPage(eq(JOB), anyLong(), any(), eq(STAGED)))
                .thenAnswer(invocation -> new StoredFile("pages/" + invocation.getArgument(1) + ".html", 42));
        when(storage.publishArchive(eq(JOB), any())).thenReturn(ARCHIVE);
    }

    @Test
    @DisplayName("Reads the sitemap of a new job, captures its pages and their assets, and publishes the archive "
            + "with every page and asset in the manifest")
    void processesANewJob() throws Exception {
        loadJob(null);
        SitemapUrls urls = new SitemapUrls(SITE, List.of(url("/a"), url("/b")), 3, 1);
        when(sitemapReader.read(SITEMAP, MAX_PAGES)).thenReturn(urls);
        when(lifecycle.pendingPages(JOB)).thenReturn(List.of(new PendingPage(1, "https://example.org/a", 0),
                new PendingPage(2, "https://example.org/b", 0)));
        FetchedResponse<Path> response = new FetchedResponse<>(url("/a"), 200, "text/html", STAGED);
        when(pageCapturer.capture(url("/a"), SITE, STAGED)).thenReturn(response);
        when(pageCapturer.capture(url("/b"), SITE, STAGED)).thenThrow(FetchException.forHttpStatus(404));
        List<PageResult> pages = List.of(
                new PageResult("https://example.org/a", PageStatus.SUCCEEDED, "pages/1.html", 200, "text/html",
                        "https://example.org/a/", 1, null),
                new PageResult("https://example.org/b", PageStatus.FAILED, null, 404, null, null, 1, "HTTP status 404"));
        when(lifecycle.pageResults(JOB)).thenReturn(pages);
        List<ArchiveManifest.Asset> assets = List.of(new ArchiveManifest.Asset("https://example.org/site.css",
                "assets/1-site.css", "https://example.org/site.css", "text/css", null));
        when(assetCapturer.captureAll(JOB, SITE, pages)).thenReturn(new AssetCapturer.Result(assets, 4));

        processor.process(JOB);

        verify(storage).prepareJobDirectory(JOB);
        verify(lifecycle).recordSitemap(JOB, urls);
        verify(lifecycle).markPageSucceeded(1, response, "pages/1.html", 42);
        verify(lifecycle).markPageFailed(2, 404, "HTTP status 404");
        assertThat(publishedManifest()).isEqualTo(new ArchiveManifest(JOB, SITEMAP.toString(), SITE.toString(), NOW,
                ArchiveManifest.SNAPSHOT_NOTICE,
                List.of(new ArchiveManifest.Entry("https://example.org/a", "SUCCEEDED", "pages/1.html", 200,
                                "text/html", "https://example.org/a/", 1, null),
                        new ArchiveManifest.Entry("https://example.org/b", "FAILED", null, 404, null, null, 1,
                                "HTTP status 404")),
                assets, 4));
        verify(lifecycle).completeJob(JOB, true, ARCHIVE,
                "1 of 2 pages could not be captured. See the page list for details.");
    }

    @Test
    @DisplayName("Resumes a job whose sitemap was processed with the site origin it recorded, without reading "
            + "the sitemap again")
    void resumesAJobWithoutReadingTheSitemapAgain() throws Exception {
        loadJob(SITE);
        List<PageResult> pages = List.of(new PageResult("https://example.org/a", PageStatus.SUCCEEDED,
                "pages/1.html", 200, "text/html", "https://example.org/a", 1, null));
        when(lifecycle.pageResults(JOB)).thenReturn(pages);
        when(assetCapturer.captureAll(JOB, SITE, pages)).thenReturn(new AssetCapturer.Result(List.of(), 0));

        processor.process(JOB);

        verifyNoInteractions(sitemapReader);
        verify(lifecycle, never()).recordSitemap(any(), any());
        verify(lifecycle).completeJob(JOB, false, ARCHIVE, null);
    }

    @Test
    @DisplayName("Retries reading the sitemap and fails the job with the reason if it cannot be read")
    void failsTheJobWhenTheSitemapCannotBeRead() throws Exception {
        loadJob(null);
        when(sitemapReader.read(SITEMAP, MAX_PAGES)).thenThrow(new SitemapException("Server unreachable.", true));

        processor.process(JOB);

        verify(sitemapReader, times(MAX_ATTEMPTS)).read(SITEMAP, MAX_PAGES);
        verify(lifecycle).failJob(JOB, "The sitemap could not be processed. Server unreachable.");
        verify(lifecycle, never()).pendingPages(any());
        verifyNoInteractions(pageCapturer, assetCapturer);
    }

    @Test
    @DisplayName("Records every attempt at a page and discards the staged file of each, so a retry starts afresh")
    void recordsAndCleansUpEveryAttemptAtAPage() throws Exception {
        loadJob(SITE);
        when(lifecycle.pendingPages(JOB)).thenReturn(List.of(new PendingPage(1, "https://example.org/a", 0)));
        FetchedResponse<Path> response = new FetchedResponse<>(url("/a"), 200, "text/html", STAGED);
        when(pageCapturer.capture(url("/a"), SITE, STAGED))
                .thenThrow(FetchException.retryable("timeout"))
                .thenReturn(response);

        processor.process(JOB);

        verify(lifecycle, times(2)).recordAttemptStarted(1);
        verify(storage, times(2)).discard(STAGED);
        verify(lifecycle).markPageSucceeded(1, response, "pages/1.html", 42);
    }

    @Test
    @DisplayName("Gives a page only the attempts that interrupted runs left it, and fails it without a request "
            + "when none are left")
    void limitsAPageToTheAttemptsItHasLeft() throws Exception {
        loadJob(SITE);
        when(lifecycle.pendingPages(JOB)).thenReturn(List.of(
                new PendingPage(1, "https://example.org/a", MAX_ATTEMPTS - 1),
                new PendingPage(2, "https://example.org/b", MAX_ATTEMPTS)));
        when(pageCapturer.capture(url("/a"), SITE, STAGED)).thenThrow(FetchException.retryable("timeout"));

        processor.process(JOB);

        verify(pageCapturer).capture(url("/a"), SITE, STAGED);
        verify(lifecycle).markPageFailed(1, null, "timeout");
        verify(pageCapturer, never()).capture(eq(url("/b")), any(), any());
        verify(lifecycle, never()).recordAttemptStarted(2);
        verify(lifecycle).markPageFailed(2, null, "No attempts left after 3 interrupted attempts");
    }

    @Test
    @DisplayName("Fails the job without capturing assets or publishing an archive when no page was captured")
    void failsTheJobWhenNoPageWasCaptured() throws Exception {
        loadJob(SITE);
        PageResult failed = new PageResult("https://example.org/a", PageStatus.FAILED, null, 404, null, null, 1,
                "HTTP status 404");
        when(lifecycle.pageResults(JOB)).thenReturn(List.of(failed, failed));

        processor.process(JOB);

        verify(lifecycle).failJob(JOB, "None of the 2 pages could be captured.");
        verifyNoInteractions(assetCapturer);
        verify(storage, never()).publishArchive(any(), any());
        verify(lifecycle, never()).completeJob(any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("Deletes the captured files only once the job has completed, as a crash before that runs it again")
    void deletesTheCapturedFilesAfterCompletingTheJob() throws Exception {
        loadCapturedJob();

        processor.process(JOB);

        InOrder order = inOrder(storage, lifecycle);
        order.verify(storage).publishArchive(eq(JOB), any());
        order.verify(lifecycle).completeJob(JOB, false, ARCHIVE, null);
        order.verify(storage).deleteCapturedFiles(JOB);
    }

    @Test
    @DisplayName("Keeps the captured files if the job could not be completed")
    void keepsTheCapturedFilesIfTheJobCannotBeCompleted() throws Exception {
        loadCapturedJob();
        doThrow(new IllegalStateException("database down")).when(lifecycle).completeJob(JOB, false, ARCHIVE, null);

        assertThatIllegalStateException().isThrownBy(() -> processor.process(JOB));

        verify(storage, never()).deleteCapturedFiles(any());
    }

    @Test
    @DisplayName("A job whose captured files cannot be deleted stays completed")
    void ignoresAFailureToDeleteTheCapturedFiles() throws Exception {
        loadCapturedJob();
        doThrow(new UncheckedIOException(new IOException("busy"))).when(storage).deleteCapturedFiles(JOB);

        processor.process(JOB);

        verify(lifecycle).completeJob(JOB, false, ARCHIVE, null);
    }

    /** A resumed job with one captured page and no assets, ready to be packaged. */
    private void loadCapturedJob() throws Exception {
        loadJob(SITE);
        List<PageResult> pages = List.of(new PageResult("https://example.org/a", PageStatus.SUCCEEDED,
                "pages/1.html", 200, "text/html", "https://example.org/a", 1, null));
        when(lifecycle.pageResults(JOB)).thenReturn(pages);
        when(assetCapturer.captureAll(JOB, SITE, pages)).thenReturn(new AssetCapturer.Result(List.of(), 0));
    }

    private void loadJob(WebOrigin recordedOrigin) {
        when(lifecycle.loadJob(JOB)).thenReturn(new JobSnapshot(JOB, SITEMAP, MAX_PAGES, recordedOrigin));
    }

    private ArchiveManifest publishedManifest() {
        ArgumentCaptor<ArchiveManifest> manifest = ArgumentCaptor.forClass(ArchiveManifest.class);
        verify(storage).publishArchive(eq(JOB), manifest.capture());
        return manifest.getValue();
    }

    private static URI url(String path) {
        return URI.create("https://example.org" + path);
    }
}
