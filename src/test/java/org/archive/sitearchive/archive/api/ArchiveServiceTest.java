package org.archive.sitearchive.archive.api;

import jakarta.persistence.EntityManager;
import org.archive.sitearchive.PostgresTestConfiguration;
import org.archive.sitearchive.archive.ArchiveJob;
import org.archive.sitearchive.archive.ArchiveJobRepository;
import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.archive.sitearchive.archive.ArchiveJobs;
import org.archive.sitearchive.archive.ArchivePageRepository;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.archive.api.dto.ArchiveCreatedResponse;
import org.archive.sitearchive.archive.api.dto.ArchiveJobResponse;
import org.archive.sitearchive.archive.api.dto.ArchivePageResponse;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.archive.sitearchive.archive.api.dto.ProgressResponse;
import org.archive.sitearchive.archive.api.error.ArchiveFileMissingException;
import org.archive.sitearchive.archive.api.error.ArchiveNotFoundException;
import org.archive.sitearchive.archive.api.error.ArchiveNotReadyException;
import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.storage.ArchiveStorage;
import org.archive.sitearchive.web.PagedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.archive.sitearchive.archive.ArchiveJobStatus.COMPLETED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.COMPLETED_WITH_ERRORS;
import static org.archive.sitearchive.archive.ArchiveJobStatus.FAILED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.QUEUED;
import static org.archive.sitearchive.archive.ArchiveJobStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(PostgresTestConfiguration.class)
class ArchiveServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");
    private static final Instant NOW = T0.plusSeconds(60);
    private static final int DEFAULT_MAX_PAGES = 25;

    @TempDir
    private Path storageRoot;

    @Autowired
    private ArchiveJobRepository jobs;

    @Autowired
    private ArchivePageRepository pages;

    @Autowired
    private EntityManager entityManager;

    private ArchiveService service;

    @BeforeEach
    void createService() {
        ArchiveProperties properties = new ArchiveProperties(new ArchiveProperties.Storage(storageRoot),
                new ArchiveProperties.Limits(DEFAULT_MAX_PAGES, 100, DataSize.ofMegabytes(10), DataSize.ofMegabytes(5),
                        DataSize.ofMegabytes(25), 2000, 5, Duration.ofSeconds(5), Duration.ofSeconds(20)),
                null, null, null);
        service = new ArchiveService(jobs, pages, new ArchiveStorage(properties, JsonMapper.builder().build()),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource(value = {"null, 25", "7, 7"}, nullValues = "null")
    @DisplayName("createJob queues the canonical sitemap URL, with the default page limit if none is given")
    void createsAJob(Integer requestedMaxPages, int storedMaxPages) {
        ArchiveCreatedResponse created = service.createJob(
                new CreateArchiveRequest(" HTTPS://Example.ORG:443/sitemap.xml#top ", requestedMaxPages));

        assertThat(created.status()).isEqualTo(QUEUED);
        assertThat(created.createdAt()).isEqualTo(NOW);
        ArchiveJob job = reload(created.id());
        assertThat(job.getSitemapUrl()).isEqualTo("https://example.org/sitemap.xml");
        assertThat(job.getMaxPages()).isEqualTo(storedMaxPages);
    }

    @Test
    @DisplayName("getJob reports a running job, with its progress counted from its pages and nothing it does not have yet")
    void reportsARunningJob() {
        UUID job = saveJob(T0, RUNNING);
        savePages(job, PageStatus.PENDING, PageStatus.SUCCEEDED, PageStatus.SUCCEEDED, PageStatus.FAILED);

        assertThat(service.getJob(job)).isEqualTo(new ArchiveJobResponse(job, RUNNING, ArchiveJobs.SITEMAP_URL,
                ArchiveJobs.MAX_PAGES, null, null, null, new ProgressResponse(4, 1, 2, 1, 75), null, false, null,
                T0, T0, null));
    }

    @Test
    @DisplayName("getJob reports every field of a finished job")
    void reportsAFinishedJob() {
        Instant startedAt = T0.plusSeconds(1);
        Instant finishedAt = T0.plusSeconds(9);
        UUID job = saveJob(ArchiveJobs.completedWithErrors(T0, startedAt, finishedAt));

        assertThat(service.getJob(job)).isEqualTo(new ArchiveJobResponse(job, COMPLETED_WITH_ERRORS,
                ArchiveJobs.SITEMAP_URL, ArchiveJobs.MAX_PAGES, "https://example.org", 12, 1,
                new ProgressResponse(0, 0, 0, 0, 0), "1 of 11 pages could not be captured.", true, 48213L,
                T0, startedAt, finishedAt));
    }

    @Test
    @DisplayName("listJobs returns jobs newest first whatever sort is requested, each with its own progress")
    void listsJobsNewestFirst() {
        UUID oldest = saveJob(T0, COMPLETED);
        savePages(oldest, PageStatus.SUCCEEDED);
        UUID withoutPages = saveJob(T0.plusSeconds(1), FAILED);
        UUID newest = saveJob(T0.plusSeconds(2), COMPLETED);
        savePages(newest, PageStatus.SUCCEEDED, PageStatus.SUCCEEDED, PageStatus.FAILED);

        PagedResponse<ArchiveJobResponse> response = service.listJobs(null,
                PageRequest.of(0, 10, Sort.by("createdAt").ascending()));

        assertThat(response.items()).extracting(ArchiveJobResponse::id).containsExactly(newest, withoutPages, oldest);
        assertThat(response.items()).extracting(ArchiveJobResponse::progress).containsExactly(
                new ProgressResponse(3, 0, 2, 1, 100),
                new ProgressResponse(0, 0, 0, 0, 0),
                new ProgressResponse(1, 0, 1, 0, 100));
    }

    @Test
    @DisplayName("listJobs filters by status and pages the result")
    void filtersAndPagesJobs() {
        UUID oldest = saveJob(T0, COMPLETED);
        saveJob(T0.plusSeconds(1), FAILED);
        UUID middle = saveJob(T0.plusSeconds(2), COMPLETED);
        UUID newest = saveJob(T0.plusSeconds(3), COMPLETED);

        PagedResponse<ArchiveJobResponse> first = service.listJobs(COMPLETED, PageRequest.of(0, 2));
        PagedResponse<ArchiveJobResponse> second = service.listJobs(COMPLETED, PageRequest.of(1, 2));

        assertThat(first.items()).extracting(ArchiveJobResponse::id).containsExactly(newest, middle);
        assertThat(first).extracting(PagedResponse::page, PagedResponse::size, PagedResponse::totalItems,
                PagedResponse::totalPages).containsExactly(0, 2, 3L, 2);
        assertThat(second.items()).extracting(ArchiveJobResponse::id).containsExactly(oldest);
    }

    @Test
    @DisplayName("listPages returns the job's pages in sitemap order whatever sort is requested, optionally by status")
    void listsPagesInSitemapOrder() {
        UUID job = saveJob(T0, RUNNING);
        savePage(job, "/a", PageStatus.SUCCEEDED);
        savePage(job, "/b", PageStatus.FAILED);
        savePage(job, "/c", PageStatus.SUCCEEDED);
        savePage(saveJob(T0, RUNNING), "/other-job", PageStatus.SUCCEEDED);
        PageRequest newestFirst = PageRequest.of(0, 10, Sort.by("id").descending());

        assertThat(service.listPages(job, null, newestFirst).items()).extracting(ArchivePageResponse::sourceUrl)
                .containsExactly("https://example.org/a", "https://example.org/b", "https://example.org/c");
        assertThat(service.listPages(job, PageStatus.SUCCEEDED, newestFirst).items())
                .extracting(ArchivePageResponse::sourceUrl)
                .containsExactly("https://example.org/a", "https://example.org/c");
    }

    @Test
    @DisplayName("listPages reports every field of each page")
    void reportsEveryFieldOfAPage() {
        UUID job = saveJob(T0, RUNNING);
        long captured = savePage(job, "/a", PageStatus.SUCCEEDED);
        long failed = savePage(job, "/b", PageStatus.FAILED);

        assertThat(service.listPages(job, null, PageRequest.of(0, 10)).items()).containsExactly(
                new ArchivePageResponse(captured, "https://example.org/a", PageStatus.SUCCEEDED, 1, 200,
                        "https://example.org/a/final", "text/html; charset=utf-8", 123L, "pages/a.html", null,
                        ArchiveJobs.ATTEMPTED_AT, ArchiveJobs.CAPTURED_AT),
                new ArchivePageResponse(failed, "https://example.org/b", PageStatus.FAILED, 1, 404, null, null, null,
                        null, "HTTP status 404", ArchiveJobs.ATTEMPTED_AT, null));
    }

    @Test
    @DisplayName("Every lookup of an unknown archive fails with 404")
    void reportsAnUnknownArchive() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.getJob(unknown)).isInstanceOf(ArchiveNotFoundException.class);
        assertThatThrownBy(() -> service.listPages(unknown, null, PageRequest.of(0, 10)))
                .isInstanceOf(ArchiveNotFoundException.class);
        assertThatThrownBy(() -> service.getDownload(unknown)).isInstanceOf(ArchiveNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveJobStatus.class, names = {"COMPLETED", "COMPLETED_WITH_ERRORS"})
    @DisplayName("getDownload returns the ZIP of a completed job, named after the job")
    void downloadsACompletedArchive(ArchiveJobStatus status) throws IOException {
        UUID job = saveJob(T0, status);
        Path zip = storageRoot.resolve(ArchiveJobs.archiveFile(job));
        Files.createDirectories(zip.getParent());
        Files.write(zip, new byte[]{'P', 'K'});

        assertThat(service.getDownload(job)).isEqualTo(new ArchiveDownload(zip, "archive-" + job + ".zip"));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveJobStatus.class, names = {"QUEUED", "RUNNING", "FAILED"})
    @DisplayName("getDownload fails with 409 for a job without an archive, naming its status")
    void refusesToDownloadWithoutAnArchive(ArchiveJobStatus status) {
        UUID job = saveJob(T0, status);

        assertThatThrownBy(() -> service.getDownload(job))
                .isInstanceOfSatisfying(ArchiveNotReadyException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.getBody().getProperties()).containsEntry("archiveStatus", status);
                });
    }

    @Test
    @DisplayName("getDownload fails with 410 when a completed job's ZIP is no longer on disk")
    void reportsAMissingArchiveFile() {
        UUID job = saveJob(T0, COMPLETED);

        assertThatThrownBy(() -> service.getDownload(job))
                .isInstanceOfSatisfying(ArchiveFileMissingException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(410));
    }

    /** Saved and then cleared from the persistence context, so that the service reads it back from the database. */
    private UUID saveJob(Instant createdAt, ArchiveJobStatus status) {
        return saveJob(ArchiveJobs.job(createdAt, status));
    }

    private UUID saveJob(ArchiveJob job) {
        UUID id = jobs.saveAndFlush(job).getId();
        entityManager.clear();
        return id;
    }

    private long savePage(UUID job, String path, PageStatus status) {
        long id = pages.saveAndFlush(ArchiveJobs.page(job, path, status)).getId();
        entityManager.clear();
        return id;
    }

    /** Writes pending changes and reads the job back from the database rather than from the persistence context. */
    private ArchiveJob reload(UUID job) {
        entityManager.flush();
        entityManager.clear();
        return jobs.findById(job).orElseThrow();
    }

    private void savePages(UUID job, PageStatus... statuses) {
        for (int i = 0; i < statuses.length; i++) {
            savePage(job, "/" + i, statuses[i]);
        }
    }
}
