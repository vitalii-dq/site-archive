package org.archive.sitearchive.archive.api;

import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.archive.api.dto.ArchiveCreatedResponse;
import org.archive.sitearchive.archive.api.dto.ArchiveJobResponse;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.archive.sitearchive.archive.api.dto.ProgressResponse;
import org.archive.sitearchive.archive.api.error.ArchiveFileMissingException;
import org.archive.sitearchive.archive.api.error.ArchiveNotFoundException;
import org.archive.sitearchive.archive.api.error.ArchiveNotReadyException;
import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.web.PagedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The HTTP contract documented in the README: status codes, problem details, paging and the download headers. The
 * service is mocked; the MVC configuration, including API versioning and paging limits, is the application's own.
 */
@WebMvcTest(ArchiveController.class)
class ArchiveControllerTest {

    private static final UUID ID = UUID.fromString("0b7e3c55-5d0e-4a8e-a0a4-2f3bb1a1e4c9");
    private static final Instant CREATED_AT = Instant.parse("2026-10-27T18:00:00Z");
    private static final String SITEMAP_URL = "https://example.org/sitemap.xml";

    /** Binds the limits from application.yml, which the request validation uses. */
    @TestConfiguration
    @EnableConfigurationProperties(ArchiveProperties.class)
    static class Properties {
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ArchiveService service;

    @Test
    @DisplayName("POST queues a job and answers 202 with its id, status and creation time, and the job's URL")
    void createsAJob() {
        when(service.createJob(new CreateArchiveRequest(SITEMAP_URL, 50)))
                .thenReturn(new ArchiveCreatedResponse(ID, ArchiveJobStatus.QUEUED, CREATED_AT));

        assertThat(mvc.post().uri("/api/v1/archives").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sitemapUrl": "%s", "maxPages": 50}""".formatted(SITEMAP_URL)))
                .hasStatus(HttpStatus.ACCEPTED)
                .hasHeader(HttpHeaders.LOCATION, "http://localhost/api/v1/archives/" + ID)
                .bodyJson().isStrictlyEqualTo("""
                        {"id": "%s", "status": "QUEUED", "createdAt": "2026-10-27T18:00:00Z"}""".formatted(ID));
    }

    static Stream<Arguments> invalidRequests() {
        String urlMessage = "must be an absolute http or https URL of at most 2048 characters, with a host and "
                + "without credentials";
        return Stream.of(
                Arguments.of("{\"maxPages\": 5}", "sitemapUrl", urlMessage),
                Arguments.of("{\"sitemapUrl\": \"ftp://example.org/sitemap.xml\"}", "sitemapUrl", urlMessage),
                Arguments.of("{\"sitemapUrl\": \"https://user:secret@example.org/\"}", "sitemapUrl", urlMessage),
                Arguments.of("{\"sitemapUrl\": \"" + SITEMAP_URL + "\", \"maxPages\": 0}", "maxPages",
                        "must be greater than 0"),
                Arguments.of("{\"sitemapUrl\": \"" + SITEMAP_URL + "\", \"maxPages\": 1001}", "maxPages",
                        "must not exceed 1000"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @DisplayName("POST rejects an invalid request with 400 and the shared validation problem format")
    void rejectsAnInvalidRequest(String body, String field, String message) {
        assertThat(mvc.post().uri("/api/v1/archives").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "title": "Validation failed",
                          "status": 400,
                          "detail": "The request contains invalid values",
                          "instance": "/api/v1/archives",
                          "errors": [{"field": "%s", "message": "%s"}]
                        }""".formatted(field, message));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/api/v1/archives/not-a-uuid       | id     | must be a valid UUID",
            "/api/v1/archives?status=DONE      | status | must be one of [QUEUED, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED]",
            "/api/v1/archives/0b7e3c55-5d0e-4a8e-a0a4-2f3bb1a1e4c9/pages?status=DONE | status | must be one of [PENDING, SUCCEEDED, FAILED]"})
    @DisplayName("GET rejects a malformed id or an unknown status with 400 and the shared validation problem format")
    void rejectsAMalformedParameter(String uri, String field, String message) {
        assertThat(mvc.get().uri(uri))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "title": "Validation failed",
                          "status": 400,
                          "errors": [{"field": "%s", "message": "%s"}]
                        }""".formatted(field, message));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST rejects a fractional maxPages with 400 instead of truncating it")
    void rejectsAFractionalPageLimit() {
        assertThat(mvc.post().uri("/api/v1/archives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sitemapUrl\": \"" + SITEMAP_URL + "\", \"maxPages\": 2.5}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("An unsupported API version is rejected with 400")
    void rejectsAnUnsupportedApiVersion() {
        assertThat(mvc.get().uri("/api/v2/archives/{id}", ID))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"status": 400, "detail": "Invalid API version: '2.0.0'."}""");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET of an unknown archive answers 404 with a problem detail")
    void reportsAnUnknownArchive() {
        when(service.getJob(ID)).thenThrow(new ArchiveNotFoundException(ID));

        assertThat(mvc.get().uri("/api/v1/archives/{id}", ID))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "title": "Archive not found",
                          "status": 404,
                          "detail": "Archive %1$s was not found",
                          "instance": "/api/v1/archives/%1$s"
                        }""".formatted(ID));
    }

    @Test
    @DisplayName("Download of an unfinished archive answers 409 with its current status")
    void refusesToDownloadAnUnfinishedArchive() {
        when(service.getDownload(ID)).thenThrow(new ArchiveNotReadyException(ID, ArchiveJobStatus.RUNNING));

        assertThat(mvc.get().uri("/api/v1/archives/{id}/download", ID))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"title": "Archive not ready", "status": 409, "archiveStatus": "RUNNING"}""");
    }

    @Test
    @DisplayName("Download of an archive whose ZIP is gone answers 410")
    void reportsAMissingArchiveFile() {
        when(service.getDownload(ID)).thenThrow(new ArchiveFileMissingException(ID));

        assertThat(mvc.get().uri("/api/v1/archives/{id}/download", ID))
                .hasStatus(HttpStatus.GONE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"title": "Archive file missing", "status": 410}""");
    }

    @Test
    @DisplayName("Download serves the ZIP as an attachment that browsers must not sniff")
    void downloadsTheArchive(@TempDir Path directory) throws IOException {
        byte[] zip = {0x50, 0x4b, 0x05, 0x06};
        Path file = Files.write(directory.resolve("archive.zip"), zip);
        when(service.getDownload(ID)).thenReturn(new ArchiveDownload(file, "archive-" + ID + ".zip"));

        assertThat(mvc.get().uri("/api/v1/archives/{id}/download", ID))
                .hasStatusOk()
                .hasContentType("application/zip")
                .hasHeader("Content-Disposition", "attachment; filename=\"archive-" + ID + ".zip\"")
                .hasHeader("X-Content-Type-Options", "nosniff")
                .body().isEqualTo(zip);
    }

    @Test
    @DisplayName("An unexpected error answers 500 without revealing its message")
    void hidesUnexpectedErrors() {
        when(service.getJob(ID)).thenThrow(new IllegalStateException("connection to 10.0.0.5 refused"));

        assertThat(mvc.get().uri("/api/v1/archives/{id}", ID))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"title": "Internal error", "status": 500, "detail": "An unexpected error occurred"}""");
        assertThat(mvc.get().uri("/api/v1/archives/{id}", ID)).bodyText().doesNotContain("10.0.0.5");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''                  | 0 | 20",
            "page=2&size=50      | 2 | 50",
            "page=2&size=500     | 2 | 100",
            "page=-1&size=abc    | 0 | 20",
            "page=abc&size=0     | 0 | 20"})
    @DisplayName("Lists use 0-based pages of 20 by default, cap the size at 100 and fall back on invalid values")
    void bindsPaging(String query, int page, int size) {
        when(service.listJobs(any(), any())).thenReturn(new PagedResponse<>(List.of(), page, size, 0, 0));

        assertThat(mvc.get().uri("/api/v1/archives?" + query)).hasStatusOk();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listJobs(eq(null), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(page);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(size);
    }

    @Test
    @DisplayName("Lists pass the status filter on and answer with the paged envelope")
    void listsPagesWithAStatus() {
        when(service.listPages(eq(ID), eq(PageStatus.FAILED), any()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0));

        assertThat(mvc.get().uri("/api/v1/archives/{id}/pages?status=FAILED", ID))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"items": [], "page": 0, "size": 20, "totalItems": 0, "totalPages": 0}""");
    }

    @Test
    @DisplayName("GET of a job answers with its status and progress, including fields it does not have yet")
    void getsAJob() {
        when(service.getJob(ID)).thenReturn(new ArchiveJobResponse(ID, ArchiveJobStatus.QUEUED, SITEMAP_URL, 50, null,
                null, null, new ProgressResponse(0, 0, 0, 0, 0), null, false,
                null, CREATED_AT, null, null));

        assertThat(mvc.get().uri("/api/v1/archives/{id}", ID))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": "%s",
                          "status": "QUEUED",
                          "sitemapUrl": "%s",
                          "maxPages": 50,
                          "siteOrigin": null,
                          "sitemapUrlCount": null,
                          "excludedUrlCount": null,
                          "progress": {"totalPages": 0, "pendingPages": 0, "succeededPages": 0, "failedPages": 0,
                                       "percentComplete": 0},
                          "errorSummary": null,
                          "archiveAvailable": false,
                          "archiveSizeBytes": null,
                          "createdAt": "2026-10-27T18:00:00Z",
                          "startedAt": null,
                          "finishedAt": null
                        }""".formatted(ID, SITEMAP_URL));
    }
}
