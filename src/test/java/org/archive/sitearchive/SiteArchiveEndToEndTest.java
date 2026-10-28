package org.archive.sitearchive;

import org.archive.sitearchive.archive.ArchiveJob;
import org.archive.sitearchive.archive.ArchiveJobRepository;
import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.archive.api.dto.ArchiveCreatedResponse;
import org.archive.sitearchive.archive.api.dto.ArchiveJobResponse;
import org.archive.sitearchive.archive.api.dto.ArchivePageResponse;
import org.archive.sitearchive.archive.api.dto.ProgressResponse;
import org.archive.sitearchive.web.PagedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * The whole service, against PostgreSQL and a {@link FakeSite}: jobs are created and inspected over HTTP, and
 * processed by the real workers. Workers keep running between tests, so each test only looks at its own jobs, and
 * waits for them to finish.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "archive.worker.concurrency=2",
        "archive.worker.poll-interval=50ms",
        "archive.worker.max-crashes-per-job=" + SiteArchiveEndToEndTest.MAX_CRASHES,
        "archive.retry.max-attempts=3",
        "archive.retry.initial-backoff=10ms",
        "archive.retry.max-backoff=50ms",
        "archive.limits.request-timeout=10s"})
@Import(PostgresTestConfiguration.class)
class SiteArchiveEndToEndTest {

    static final int MAX_CRASHES = 3;
    private static final Duration FINISH_TIMEOUT = Duration.ofSeconds(30);
    private static final String HTML = "text/html; charset=utf-8";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff};

    @TempDir
    private static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("archive.storage.root", () -> storageRoot.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ArchiveJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    private final FakeSite site = new FakeSite();
    private RestTestClient api;
    private SmartLifecycle workers;

    @BeforeEach
    void setUp() {
        api = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/api/v1/archives").build();
        workers = context.getBean("archiveWorkers", SmartLifecycle.class);
    }

    @AfterEach
    void tearDown() {
        if (!workers.isRunning()) {
            workers.start();
        }
        site.close();
    }

    @Test
    @DisplayName("Archives a site: the queued job completes, and its ZIP holds the pages and the assets they use, "
            + "with references pointing at the copies")
    void archivesASite() throws IOException {
        site.sitemap("/sitemap.xml", site.url("/"), site.url("/about"))
                .html("/", "<html><head><link rel=\"stylesheet\" href=\"/css/site.css\"></head>"
                        + "<body><img src=\"/img/logo.png\"></body></html>")
                .html("/about", "<html><body><img src=\"/img/logo.png\"></body></html>")
                .serve("/css/site.css", "text/css", "body{background:url(/img/bg.png)}")
                .serve("/img/bg.png", "image/png", PNG)
                .serve("/img/logo.png", "image/png", PNG);

        ArchiveCreatedResponse created = create(site.url("/sitemap.xml"), null);
        ArchiveJobResponse job = awaitFinished(created.id());

        assertThat(created.status()).isEqualTo(ArchiveJobStatus.QUEUED);
        assertThat(job.status()).isEqualTo(ArchiveJobStatus.COMPLETED);
        assertThat(job.maxPages()).isEqualTo(100);
        assertThat(job.siteOrigin()).isEqualTo(site.origin());
        assertThat(job.sitemapUrlCount()).isEqualTo(2);
        assertThat(job.excludedUrlCount()).isZero();
        assertThat(job.progress()).isEqualTo(new ProgressResponse(2, 0, 2, 0, 100));
        assertThat(job.errorSummary()).isNull();
        assertThat(job.archiveAvailable()).isTrue();

        byte[] archive = download(created.id());
        assertThat(job.archiveSizeBytes()).isEqualTo(archive.length);
        List<String> pageFiles = pages(created.id(), null).stream().map(ArchivePageResponse::fileName).toList();
        Map<String, byte[]> zip = unzip(archive);
        List<String> expectedFiles = new ArrayList<>(List.of("index.html", "manifest.json"));
        expectedFiles.addAll(pageFiles);
        expectedFiles.addAll(List.of("assets/1-css-site.css", "assets/2-img-bg.png", "assets/3-img-logo.png"));
        assertThat(zip).containsOnlyKeys(expectedFiles);
        assertThat(text(zip, pageFiles.getFirst()))
                .contains("<link rel=\"stylesheet\" href=\"../assets/1-css-site.css\">")
                .contains("<img src=\"../assets/3-img-logo.png\">");
        assertThat(text(zip, "assets/1-css-site.css")).isEqualTo("body{background:url(\"2-img-bg.png\")}");
        assertThat(zip.get("assets/3-img-logo.png")).isEqualTo(PNG);
    }

    @Test
    @DisplayName("Completes with errors when some pages fail, lists those pages with their reason, and still "
            + "offers the ZIP")
    void completesWithErrorsWhenSomePagesFail() throws IOException {
        site.sitemap("/sitemap.xml", site.url("/ok"), site.url("/missing"), site.url("/report.pdf"))
                .html("/ok", "<p>ok</p>")
                .serve("/report.pdf", "application/pdf", "%PDF-1.7");

        UUID id = create(site.url("/sitemap.xml"), null).id();
        ArchiveJobResponse job = awaitFinished(id);

        assertThat(job.status()).isEqualTo(ArchiveJobStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.errorSummary()).isEqualTo("2 of 3 pages could not be captured. See the page list for details.");
        assertThat(job.progress()).isEqualTo(new ProgressResponse(3, 0, 1, 2, 100));
        assertThat(pages(id, PageStatus.FAILED))
                .extracting(ArchivePageResponse::sourceUrl, ArchivePageResponse::httpStatus, ArchivePageResponse::error)
                .containsExactly(
                        tuple(site.url("/missing"), 404, "HTTP status 404"),
                        tuple(site.url("/report.pdf"), 200, "Not an HTML page (Content-Type: application/pdf)"));
        String okPage = pages(id, PageStatus.SUCCEEDED).getFirst().fileName();
        assertThat(unzip(download(id))).containsOnlyKeys("index.html", "manifest.json", okPage);
    }

    @Test
    @DisplayName("Retries a page after a server error, counting both attempts")
    void retriesAPageAfterAServerError() {
        AtomicInteger requests = new AtomicInteger();
        site.sitemap("/sitemap.xml", site.url("/flaky"))
                .handle("/flaky", exchange -> FakeSite.respond(exchange, requests.getAndIncrement() == 0 ? 503 : 200,
                        HTML, "<p>back</p>".getBytes(StandardCharsets.UTF_8)));

        UUID id = create(site.url("/sitemap.xml"), null).id();

        assertThat(awaitFinished(id).status()).isEqualTo(ArchiveJobStatus.COMPLETED);
        assertThat(pages(id, null)).singleElement().satisfies(page -> {
            assertThat(page.status()).isEqualTo(PageStatus.SUCCEEDED);
            assertThat(page.attempts()).isEqualTo(2);
            assertThat(page.httpStatus()).isEqualTo(200);
        });
    }

    @Test
    @DisplayName("Fails the job when the sitemap cannot be downloaded, with no ZIP to download")
    void failsTheJobWhenTheSitemapCannotBeDownloaded() {
        UUID id = create(site.url("/sitemap.xml"), null).id();
        ArchiveJobResponse job = awaitFinished(id);

        assertThat(job.status()).isEqualTo(ArchiveJobStatus.FAILED);
        assertThat(job.errorSummary()).isEqualTo(
                "The sitemap could not be processed. The sitemap could not be downloaded: HTTP status 404");
        assertThat(job.sitemapUrlCount()).isNull();
        assertNoDownload(id);
    }

    @Test
    @DisplayName("Fails the job when none of its pages can be captured, with no ZIP to download")
    void failsTheJobWhenNoPageCanBeCaptured() {
        site.sitemap("/sitemap.xml", site.url("/a"), site.url("/b"));

        UUID id = create(site.url("/sitemap.xml"), null).id();
        ArchiveJobResponse job = awaitFinished(id);

        assertThat(job.status()).isEqualTo(ArchiveJobStatus.FAILED);
        assertThat(job.errorSummary()).isEqualTo("None of the 2 pages could be captured.");
        assertThat(job.progress()).isEqualTo(new ProgressResponse(2, 0, 0, 2, 100));
        assertNoDownload(id);
    }

    @Test
    @DisplayName("Captures only the first maxPages pages of the site's own origin, and no files from other sites")
    void staysWithinTheSiteAndThePageLimit() throws IOException {
        try (FakeSite otherSite = new FakeSite()) {
            otherSite.html("/elsewhere", "<p>elsewhere</p>").serve("/logo.png", "image/png", PNG);
            site.sitemap("/sitemap.xml", site.url("/one"), otherSite.url("/elsewhere"), site.url("/two"),
                            site.url("/three"))
                    .html("/one", "<img src=\"" + otherSite.url("/logo.png") + "\"><img src=\"/local.png\">")
                    .html("/two", "<p>two</p>")
                    .html("/three", "<p>three</p>")
                    .serve("/local.png", "image/png", PNG);

            UUID id = create(site.url("/sitemap.xml"), 2).id();
            ArchiveJobResponse job = awaitFinished(id);

            assertThat(job.status()).isEqualTo(ArchiveJobStatus.COMPLETED);
            assertThat(job.sitemapUrlCount()).isEqualTo(4);
            assertThat(job.excludedUrlCount()).isEqualTo(2);
            List<ArchivePageResponse> pages = pages(id, null);
            assertThat(pages).extracting(ArchivePageResponse::sourceUrl)
                    .containsExactly(site.url("/one"), site.url("/two"));
            assertThat(site.requests("/three")).isZero();
            assertThat(otherSite.totalRequests()).isZero();
            Map<String, byte[]> zip = unzip(download(id));
            assertThat(zip).containsOnlyKeys("index.html", "manifest.json", pages.get(0).fileName(),
                    pages.get(1).fileName(), "assets/1-local.png");
            assertThat(text(zip, pages.getFirst().fileName()))
                    .contains("<img src=\"" + otherSite.url("/logo.png") + "\">");
        }
    }

    @Test
    @DisplayName("Stopping the workers mid-job requeues the job without counting a crash, and the next run "
            + "completes it without fetching the sitemap or the captured pages again")
    void resumesAJobStoppedByAShutdown() throws Exception {
        CountDownLatch secondPageRequested = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean blocking = new AtomicBoolean(true);
        site.sitemap("/sitemap.xml", site.url("/first"), site.url("/second"))
                .html("/first", "<p>first</p>")
                .handle("/second", exchange -> {
                    if (blocking.get()) {
                        secondPageRequested.countDown();
                        released.await();
                    }
                    FakeSite.respond(exchange, 200, HTML, "<p>second</p>".getBytes(StandardCharsets.UTF_8));
                });
        UUID id = create(site.url("/sitemap.xml"), null).id();
        assertThat(secondPageRequested.await(FINISH_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        workers.stop();
        blocking.set(false);
        released.countDown();

        assertThat(job(id).status()).isEqualTo(ArchiveJobStatus.QUEUED);
        assertThat(jobs.findById(id).orElseThrow().getCrashCount()).isZero();

        workers.start();

        assertThat(awaitFinished(id).status()).isEqualTo(ArchiveJobStatus.COMPLETED);
        assertThat(site.requests("/sitemap.xml")).isEqualTo(1);
        assertThat(site.requests("/first")).isEqualTo(1);
        assertThat(pages(id, null))
                .extracting(ArchivePageResponse::sourceUrl, ArchivePageResponse::status, ArchivePageResponse::attempts)
                .containsExactly(
                        tuple(site.url("/first"), PageStatus.SUCCEEDED, 1),
                        tuple(site.url("/second"), PageStatus.SUCCEEDED, 2));
    }

    @Test
    @DisplayName("On start, a job left running by a crash is requeued, counting the crash, and completed")
    void requeuesAJobLeftRunningByACrash() {
        site.sitemap("/sitemap.xml", site.url("/page")).html("/page", "<p>page</p>");
        workers.stop();
        UUID id = jobLeftRunningByACrash(site.url("/sitemap.xml"), 0);

        workers.start();

        assertThat(awaitFinished(id).status()).isEqualTo(ArchiveJobStatus.COMPLETED);
        assertThat(jobs.findById(id).orElseThrow().getCrashCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("On start, a job left running by its last allowed crash is failed instead of being run again")
    void failsAJobOnItsLastAllowedCrash() {
        site.sitemap("/sitemap.xml", site.url("/page")).html("/page", "<p>page</p>");
        workers.stop();
        UUID id = jobLeftRunningByACrash(site.url("/sitemap.xml"), MAX_CRASHES - 1);

        workers.start();

        ArchiveJobResponse job = awaitFinished(id);
        assertThat(job.status()).isEqualTo(ArchiveJobStatus.FAILED);
        assertThat(job.errorSummary()).startsWith(
                "The archive job was abandoned after a crash of the service or a fatal error stopped it 3 times.");
        assertThat(site.totalRequests()).isZero();
    }

    @Test
    @DisplayName("Workers process jobs side by side, and each job, so each page, exactly once")
    void processesEachJobOnceWithConcurrentWorkers() {
        int jobCount = 4;
        CountDownLatch twoPagesInFlight = new CountDownLatch(2);
        Queue<Boolean> overlapped = new ConcurrentLinkedQueue<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= jobCount; i++) {
            site.sitemap("/" + i + "/sitemap.xml", site.url("/" + i + "/page"))
                    .handle("/" + i + "/page", exchange -> {
                        twoPagesInFlight.countDown();
                        overlapped.add(twoPagesInFlight.await(5, TimeUnit.SECONDS));
                        FakeSite.respond(exchange, 200, HTML, "<p>page</p>".getBytes(StandardCharsets.UTF_8));
                    });
            ids.add(create(site.url("/" + i + "/sitemap.xml"), null).id());
        }

        for (UUID id : ids) {
            assertThat(awaitFinished(id).status()).isEqualTo(ArchiveJobStatus.COMPLETED);
        }
        assertThat(overlapped).containsOnly(true);
        for (int i = 1; i <= jobCount; i++) {
            assertThat(site.requests("/" + i + "/sitemap.xml")).isEqualTo(1);
            assertThat(site.requests("/" + i + "/page")).isEqualTo(1);
        }
    }

    private ArchiveCreatedResponse create(String sitemapUrl, Integer maxPages) {
        Map<String, Object> request = new HashMap<>();
        request.put("sitemapUrl", sitemapUrl);
        if (maxPages != null) {
            request.put("maxPages", maxPages);
        }
        return api.post().contentType(MediaType.APPLICATION_JSON).body(request).exchange()
                .expectStatus().isAccepted()
                .expectBody(ArchiveCreatedResponse.class).returnResult().getResponseBody();
    }

    private ArchiveJobResponse job(UUID id) {
        return api.get().uri("/{id}", id).exchange()
                .expectStatus().isOk()
                .expectBody(ArchiveJobResponse.class).returnResult().getResponseBody();
    }

    private ArchiveJobResponse awaitFinished(UUID id) {
        return await().atMost(FINISH_TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> job(id), job -> job.status().isFinished());
    }

    /** The job's pages in sitemap order, optionally only those in the status. */
    private List<ArchivePageResponse> pages(UUID id, PageStatus status) {
        return api.get().uri(uri -> uri.path("/{id}/pages").queryParamIfPresent("status", Optional.ofNullable(status))
                        .build(id))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PagedResponse<ArchivePageResponse>>() {
                })
                .returnResult().getResponseBody().items();
    }

    private byte[] download(UUID id) {
        return api.get().uri("/{id}/download", id).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/zip")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"archive-" + id + ".zip\"")
                .expectBody(byte[].class).returnResult().getResponseBody();
    }

    private void assertNoDownload(UUID id) {
        api.get().uri("/{id}/download", id).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("Archive " + id + " failed and has no download");
    }

    /** A job as a killed process leaves it: RUNNING, with the crashes it had before. */
    private UUID jobLeftRunningByACrash(String sitemapUrl, int earlierCrashes) {
        UUID id = jobs.save(ArchiveJob.queue(sitemapUrl, 10, Instant.now())).getId();
        jdbc.update("UPDATE archive_job SET status = 'RUNNING', started_at = created_at, crash_count = ? WHERE id = ?",
                earlierCrashes, id);
        return id;
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> zip, String file) {
        return new String(zip.get(file), StandardCharsets.UTF_8);
    }
}
