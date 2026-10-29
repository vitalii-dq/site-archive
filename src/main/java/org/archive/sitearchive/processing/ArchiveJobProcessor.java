package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.ArchiveJobLifecycle;
import org.archive.sitearchive.archive.JobSnapshot;
import org.archive.sitearchive.archive.PageResult;
import org.archive.sitearchive.archive.PendingPage;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Runs one archive job from start to finish: read the sitemap, capture each page in turn, capture the assets
 * the pages use from the site's origin, then publish the ZIP. Not transactional; every state change is its own
 * short transaction in {@link ArchiveJobLifecycle}.
 * <p>
 * Safe to run again for a job that was interrupted: the sitemap stage runs only once, captured pages are
 * skipped, assets are captured again, and every file is replaced atomically.
 */
@Component
class ArchiveJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ArchiveJobProcessor.class);

    private final ArchiveJobLifecycle lifecycle;
    private final SitemapReader sitemapReader;
    private final PageCapturer pageCapturer;
    private final AssetCapturer assetCapturer;
    private final ArchiveStorage storage;
    private final Retrier retrier;
    private final Clock clock;

    ArchiveJobProcessor(ArchiveJobLifecycle lifecycle, SitemapReader sitemapReader, PageCapturer pageCapturer,
                        AssetCapturer assetCapturer, ArchiveStorage storage, Retrier retrier, Clock clock) {
        this.lifecycle = lifecycle;
        this.sitemapReader = sitemapReader;
        this.pageCapturer = pageCapturer;
        this.assetCapturer = assetCapturer;
        this.storage = storage;
        this.retrier = retrier;
        this.clock = clock;
    }

    void process(UUID jobId) throws InterruptedException {
        log.info("Processing archive job {}", jobId);
        try {
            JobSnapshot job = lifecycle.loadJob(jobId);
            storage.prepareJobDirectory(jobId);
            WebOrigin siteOrigin = job.sitemapProcessed() ? job.siteOrigin() : processSitemap(job);

            for (PendingPage page : lifecycle.pendingPages(jobId)) {
                capturePage(jobId, page, siteOrigin);
            }
            List<PageResult> pages = lifecycle.pageResults(jobId);
            if (pages.stream().noneMatch(PageResult::captured)) {
                lifecycle.failJob(jobId, "None of the %d pages could be captured.".formatted(pages.size()));
                log.info("Archive job {} failed: no pages captured", jobId);
                return;
            }
            AssetCapturer.Result assets = captureAssets(jobId, siteOrigin, pages);
            finish(job, siteOrigin, pages, assets);

        } catch (SitemapException e) {
            log.info("Archive job {} failed: {}", jobId, e.getMessage());
            lifecycle.failJob(jobId, "The sitemap could not be processed. " + e.getMessage());
        }
    }

    private WebOrigin processSitemap(JobSnapshot job) throws SitemapException, InterruptedException {
        SitemapUrls urls = retrier.run(retrier.maxAttempts(),
                () -> sitemapReader.read(job.sitemapUrl(), job.maxPages()));
        lifecycle.recordSitemap(job.id(), urls);
        log.info("Archive job {}: {} pages selected from {} sitemap entries ({} excluded)",
                job.id(), urls.pageUrls().size(), urls.listedUrlCount(), urls.excludedUrlCount());
        return urls.origin();
    }

    private void capturePage(UUID jobId, PendingPage page, WebOrigin siteOrigin) throws InterruptedException {
        int attemptsLeft = retrier.maxAttempts() - page.attempts();
        if (attemptsLeft <= 0) {
            lifecycle.markPageFailed(page.id(), null, "No attempts left after " + page.attempts() + " interrupted attempts");
            return;
        }
        URI sourceUrl = URI.create(page.sourceUrl());
        try {
            retrier.run(attemptsLeft, () -> {
                lifecycle.recordAttemptStarted(page.id());
                Path staged = storage.newStagingFile(jobId);
                try {
                    FetchedResponse<Path> response = pageCapturer.capture(sourceUrl, siteOrigin, staged);
                    StoredFile file = storage.publishPage(jobId, page.id(), sourceUrl, staged);
                    lifecycle.markPageSucceeded(page.id(), response, file.fileName(), file.sizeBytes());
                } finally {
                    storage.discard(staged);
                }
            });
        } catch (FetchException e) {
            log.info("Archive job {}: page {} failed: {}", jobId, page.sourceUrl(), e.getMessage());
            lifecycle.markPageFailed(page.id(), e.httpStatus(), e.getMessage());
        }
    }

    /**
     * Runs after all pages, because assets are found in the captured page files. Nothing about them is stored in
     * the database, so an interrupted job captures them again.
     */
    private AssetCapturer.Result captureAssets(UUID jobId, WebOrigin siteOrigin, List<PageResult> pages)
            throws InterruptedException {
        AssetCapturer.Result assets = assetCapturer.captureAll(jobId, siteOrigin, pages);
        log.info("Archive job {}: {} of {} assets captured", jobId,
                assets.assets().stream().filter(ArchiveManifest.Asset::captured).count(), assets.assets().size());
        return assets;
    }

    /** Publishes the ZIP, completes the job, then deletes the captured files. At least one page was captured. */
    private void finish(JobSnapshot job, WebOrigin siteOrigin, List<PageResult> pages,
                        AssetCapturer.Result assets) {
        long succeeded = pages.stream().filter(PageResult::captured).count();
        long failed = pages.size() - succeeded;
        List<ArchiveManifest.Entry> entries = pages.stream().map(ArchiveJobProcessor::manifestEntry).toList();
        ArchiveManifest manifest = new ArchiveManifest(job.id(), job.sitemapUrl().toString(), siteOrigin.toString(),
                clock.instant(), ArchiveManifest.SNAPSHOT_NOTICE, entries, assets.assets(), assets.skipped());
        StoredArchive archive = storage.publishArchive(job.id(), manifest);
        String errorSummary = failed == 0
                ? null
                : "%d of %d pages could not be captured. See the page list for details.".formatted(failed, pages.size());
        lifecycle.completeJob(job.id(), failed > 0, archive, errorSummary);
        log.info("Archive job {} completed: {} of {} pages captured", job.id(), succeeded, pages.size());
        deleteCapturedFiles(job.id());
    }

    /**
     * The ZIP holds everything now. Only once the job has completed, though: until then a crash runs it again,
     * which needs the captured pages. The job stays completed if this fails; the files only take up space.
     */
    private void deleteCapturedFiles(UUID jobId) {
        try {
            storage.deleteCapturedFiles(jobId);
        } catch (RuntimeException e) {
            log.warn("Archive job {}: could not delete the captured files after packaging them", jobId, e);
        }
    }

    private static ArchiveManifest.Entry manifestEntry(PageResult page) {
        return new ArchiveManifest.Entry(page.sourceUrl(), page.status().name(), page.fileName(), page.httpStatus(),
                page.contentType(), page.finalUrl(), page.attempts(), page.errorMessage());
    }
}
