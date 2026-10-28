package org.archive.sitearchive.processing;

import org.archive.sitearchive.archive.PageResult;
import org.archive.sitearchive.assets.CssAssetLinks;
import org.archive.sitearchive.assets.HtmlAssetLinks;
import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.FetchException;
import org.archive.sitearchive.fetch.FetchedResponse;
import org.archive.sitearchive.fetch.HttpFetcher;
import org.archive.sitearchive.fetch.WebOrigin;
import org.archive.sitearchive.storage.ArchiveManifest;
import org.archive.sitearchive.storage.ArchiveStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;

/**
 * Captures the images, stylesheets, scripts and fonts that the captured pages use from the site's origin, once
 * each. Stylesheets can use further assets (imports, fonts, images), which are captured in turn.
 * <p>
 * Nothing is stored in the database: this runs after all pages, reads them from disk, and an interrupted job
 * simply captures its assets again. A failed asset never fails its page or the job.
 */
@Component
class AssetCapturer {

    private static final Logger log = LoggerFactory.getLogger(AssetCapturer.class);

    private final HttpFetcher fetcher;
    private final ArchiveStorage storage;
    private final Retrier retrier;
    private final long maxAssetBytes;
    private final int maxAssetsPerJob;

    AssetCapturer(HttpFetcher fetcher, ArchiveStorage storage, Retrier retrier, ArchiveProperties properties) {
        this.fetcher = fetcher;
        this.storage = storage;
        this.retrier = retrier;
        this.maxAssetBytes = properties.limits().maxAssetSize().toBytes();
        this.maxAssetsPerJob = properties.limits().maxAssetsPerJob();
    }

    /**
     * @param assets  every asset attempted, captured or not
     * @param skipped assets found but not attempted because the per-job limit was reached. Stylesheets among them
     *                were not read, so the files they use are not counted.
     */
    record Result(List<ArchiveManifest.Asset> assets, int skipped) {
    }

    Result captureAll(UUID jobId, WebOrigin siteOrigin, List<PageResult> pages)
            throws InterruptedException {
        AssetQueue queue = new AssetQueue(siteOrigin);
        for (PageResult page : pages) {
            if (page.captured()) {
                byte[] html = storage.readFile(jobId, page.fileName());
                HtmlAssetLinks.Found found = HtmlAssetLinks.find(html, page.contentType(), URI.create(page.finalUrl()));
                queue.addStyling(found.styling());
                queue.addOther(found.other());
            }
        }
        List<ArchiveManifest.Asset> assets = new ArrayList<>();
        while (assets.size() < maxAssetsPerJob && !queue.isEmpty()) {
            URI url = queue.next();
            int number = assets.size() + 1;
            try {
                ArchiveManifest.Asset asset = retrier.run(retrier.maxAttempts(),
                        () -> capture(jobId, number, url, siteOrigin));
                if (CssAssetLinks.isStylesheet(asset.contentType())) {
                    byte[] css = storage.readFile(jobId, asset.file());
                    queue.addStyling(CssAssetLinks.find(css, URI.create(asset.finalUrl())));
                }
                assets.add(asset);
            } catch (FetchException e) {
                log.info("Archive job {}: asset {} failed: {}", jobId, url, e.getMessage());
                assets.add(new ArchiveManifest.Asset(url.toString(), null, null, null, e.getMessage()));
            }
        }
        if (!queue.isEmpty()) {
            log.warn("Archive job {}: limit of {} assets reached, {} more not captured",
                    jobId, maxAssetsPerJob, queue.size());
        }
        return new Result(assets, queue.size());
    }

    /** One attempt: streams the asset into a staging file and publishes it. */
    private ArchiveManifest.Asset capture(UUID jobId, int number, URI url, WebOrigin siteOrigin)
            throws FetchException, InterruptedException {
        Path staged = storage.newStagingFile(jobId);
        try {
            FetchedResponse<Path> response = fetcher.fetchToFile(url, maxAssetBytes, siteOrigin, staged).requireSuccess();
            String file = storage.publishAsset(jobId, number, url, response.contentType(), staged).fileName();
            return new ArchiveManifest.Asset(url.toString(), file, response.finalUrl().toString(),
                    response.contentType(), null);
        } finally {
            storage.discard(staged);
        }
    }

    /**
     * The site's assets still to capture, each URL once. Styling (stylesheets and the files CSS uses) is taken
     * before everything else, so that the per-job limit drops images and scripts rather than fonts and layout.
     */
    private static final class AssetQueue {

        private final WebOrigin siteOrigin;
        private final SequencedSet<URI> styling = new LinkedHashSet<>();
        private final SequencedSet<URI> other = new LinkedHashSet<>();
        private final Set<URI> taken = new HashSet<>();

        AssetQueue(WebOrigin siteOrigin) {
            this.siteOrigin = siteOrigin;
        }

        /** A URL already queued as another asset moves up, e.g. a font that is also preloaded. */
        void addStyling(Collection<URI> urls) {
            for (URI url : urls) {
                if (siteOrigin.contains(url) && !taken.contains(url)) {
                    other.remove(url);
                    styling.add(url);
                }
            }
        }

        void addOther(Collection<URI> urls) {
            for (URI url : urls) {
                if (siteOrigin.contains(url) && !taken.contains(url) && !styling.contains(url)) {
                    other.add(url);
                }
            }
        }

        boolean isEmpty() {
            return styling.isEmpty() && other.isEmpty();
        }

        int size() {
            return styling.size() + other.size();
        }

        URI next() {
            URI url = styling.isEmpty() ? other.removeFirst() : styling.removeFirst();
            taken.add(url);
            return url;
        }
    }
}
