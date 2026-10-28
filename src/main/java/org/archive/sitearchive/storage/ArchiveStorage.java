package org.archive.sitearchive.storage;

import org.archive.sitearchive.config.ArchiveProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Local file system storage, one directory per archive job:
 * <pre>
 * {root}/{jobId}/.staging/       incomplete writes
 *               /pages/*.html    captured pages, as served
 *               /assets/*        captured assets, as served (captured again on every run)
 *               /archive.zip     index.html, manifest.json, pages/ and assets/, with links rewritten
 * </pre>
 * Every file is written to {@code .staging} first and then moved into place atomically, so readers only
 * ever see complete files and re-running a job simply replaces them.
 */
@Component
public class ArchiveStorage {

    private static final String STAGING_DIRECTORY = ".staging";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String INDEX_FILE = "index.html";
    private static final String ARCHIVE_FILE = "archive.zip";

    private final Path root;
    private final JsonMapper jsonMapper;

    public ArchiveStorage(ArchiveProperties properties, JsonMapper jsonMapper) {
        this.root = properties.storage().root().toAbsolutePath().normalize();
        this.jsonMapper = jsonMapper;
    }

    /**
     * Creates the job directory and discards incomplete files and assets left behind by an interrupted run.
     * Assets are not tracked in the database, so every run captures them again.
     */
    public void prepareJobDirectory(UUID jobId) {
        try {
            Path jobDirectory = jobDirectory(jobId);
            FileSystemUtils.deleteRecursively(jobDirectory.resolve(STAGING_DIRECTORY));
            FileSystemUtils.deleteRecursively(jobDirectory.resolve(CaptureFileNames.ASSETS_DIRECTORY));
            Files.createDirectories(jobDirectory.resolve(STAGING_DIRECTORY));
            Files.createDirectories(jobDirectory.resolve(CaptureFileNames.PAGES_DIRECTORY));
            Files.createDirectories(jobDirectory.resolve(CaptureFileNames.ASSETS_DIRECTORY));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare the directory for archive " + jobId, e);
        }
    }

    /**
     * A new path in the job's staging directory, for a download in progress; the file itself does not exist yet.
     * Publish it with {@link #publishPage} or {@link #publishAsset}, or delete it with {@link #discard}.
     */
    public Path newStagingFile(UUID jobId) {
        return jobDirectory(jobId).resolve(STAGING_DIRECTORY).resolve(UUID.randomUUID() + ".tmp");
    }

    /** Moves a captured page, unmodified, from the staging directory into place. */
    public StoredFile publishPage(UUID jobId, long pageId, URI sourceUrl, Path staged) {
        return publish(jobId, staged, CaptureFileNames.forPage(pageId, sourceUrl));
    }

    /** Moves a captured asset, unmodified, from the staging directory into place. */
    public StoredFile publishAsset(UUID jobId, int assetNumber, URI sourceUrl, String contentType, Path staged) {
        return publish(jobId, staged, CaptureFileNames.forAsset(assetNumber, sourceUrl, contentType));
    }

    /** Deletes a staged file that was not published; does nothing if it was published or never created. */
    public void discard(Path staged) {
        deleteQuietly(staged);
    }

    /** Reads a captured page or asset. */
    public byte[] readFile(UUID jobId, String fileName) {
        try {
            return Files.readAllBytes(resolveInJob(jobId, fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + fileName + " of archive " + jobId, e);
        }
    }

    /**
     * Packages the index page, the manifest, the captured pages and the captured assets into the ZIP. In the
     * pages and stylesheets, references to captured assets are rewritten to point to the copies in the ZIP.
     */
    public StoredArchive publishArchive(UUID jobId, ArchiveManifest manifest) {
        Path archive = resolveInJob(jobId, ARCHIVE_FILE);
        writeAtomically(jobId, archive, out -> writeZip(jobId, manifest, out));
        try {
            return new StoredArchive(root.relativize(archive).toString(), Files.size(archive));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the size of " + archive, e);
        }
    }

    /** Resolves a path stored in the database, refusing anything outside the storage root. */
    public Path resolve(String relativePath) {
        return resolveWithin(root, relativePath);
    }

    private void writeZip(UUID jobId, ArchiveManifest manifest, OutputStream out) throws IOException {
        ArchiveLinks links = new ArchiveLinks(manifest);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(INDEX_FILE));
            zip.write(IndexPageRenderer.render(manifest).getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(MANIFEST_FILE));
            zip.write(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            for (ArchiveManifest.Entry page : manifest.capturedPages()) {
                zip.putNextEntry(new ZipEntry(page.file()));
                zip.write(links.rewritePage(page, Files.readAllBytes(resolveInJob(jobId, page.file()))));
            }
            for (ArchiveManifest.Asset asset : manifest.capturedAssets()) {
                Path file = resolveInJob(jobId, asset.file());
                zip.putNextEntry(new ZipEntry(asset.file()));
                if (ArchiveLinks.isStylesheet(asset)) {
                    zip.write(links.rewriteStylesheet(asset, Files.readAllBytes(file)));
                } else {
                    Files.copy(file, zip);
                }
            }
        }
    }

    private StoredFile publish(UUID jobId, Path staged, String fileName) {
        Path target = resolveInJob(jobId, fileName);
        try {
            long size = Files.size(staged);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(fileName, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        }
    }

    private void writeAtomically(UUID jobId, Path target, ContentWriter writer) {
        Path staged = newStagingFile(jobId);
        try {
            try (OutputStream out = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(out);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(staged);
            throw new UncheckedIOException("Could not write " + target, e);
        } catch (RuntimeException e) {
            deleteQuietly(staged);
            throw e;
        }
    }

    private Path jobDirectory(UUID jobId) {
        return root.resolve(jobId.toString());
    }

    private Path resolveInJob(UUID jobId, String fileName) {
        return resolveWithin(jobDirectory(jobId), fileName);
    }

    private static Path resolveWithin(Path directory, String relativePath) {
        Path path = directory.resolve(relativePath).normalize();
        if (!path.startsWith(directory)) {
            throw new IllegalArgumentException("Path is outside " + directory + ": " + relativePath);
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the staging directory is cleared on the next run anyway
        }
    }

    @FunctionalInterface
    private interface ContentWriter {
        void write(OutputStream out) throws IOException;
    }
}
