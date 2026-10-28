package org.archive.sitearchive.archive;

import org.archive.sitearchive.PostgresTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.archive.sitearchive.archive.PageStatus.FAILED;
import static org.archive.sitearchive.archive.PageStatus.PENDING;
import static org.archive.sitearchive.archive.PageStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(PostgresTestConfiguration.class)
class ArchivePageRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");
    private static final Sort IN_SITEMAP_ORDER = Sort.by("id");

    @Autowired
    private ArchiveJobRepository jobs;

    @Autowired
    private ArchivePageRepository pages;

    @Test
    @DisplayName("findByArchiveJobId with paging returns only the job's pages, in the requested order and page")
    void pagesTheJobsPages() {
        UUID job = job();
        long first = page(job, "/a", PENDING).getId();
        long second = page(job, "/b", SUCCEEDED).getId();
        long third = page(job, "/c", FAILED).getId();
        page(job(), "/other-job", SUCCEEDED);

        Page<ArchivePage> firstPage = pages.findByArchiveJobId(job, PageRequest.of(0, 2, IN_SITEMAP_ORDER));
        Page<ArchivePage> secondPage = pages.findByArchiveJobId(job, PageRequest.of(1, 2, IN_SITEMAP_ORDER));

        assertThat(firstPage.map(ArchivePage::getId)).containsExactly(first, second);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.map(ArchivePage::getId)).containsExactly(third);
    }

    @Test
    @DisplayName("findByArchiveJobIdAndStatus with paging returns only the job's pages in the status")
    void pagesTheJobsPagesInAStatus() {
        UUID job = job();
        page(job, "/a", PENDING);
        long first = page(job, "/b", SUCCEEDED).getId();
        page(job, "/c", FAILED);
        long second = page(job, "/d", SUCCEEDED).getId();
        page(job(), "/other-job", SUCCEEDED);

        Page<ArchivePage> result = pages.findByArchiveJobIdAndStatus(job, SUCCEEDED, PageRequest.of(0, 10, IN_SITEMAP_ORDER));

        assertThat(result.map(ArchivePage::getId)).containsExactly(first, second);
    }

    @Test
    @DisplayName("findByArchiveJobIdAndStatusOrderById projects the job's pages in the status onto PendingPage, by id")
    void projectsPendingPages() {
        UUID job = job();
        ArchivePage retried = pending(job, "/a", 2);
        page(job, "/b", SUCCEEDED);
        ArchivePage fresh = pending(job, "/c", 0);
        pending(job(), "/other-job", 0);

        List<PendingPage> result = pages.findByArchiveJobIdAndStatusOrderById(job, PENDING, PendingPage.class);

        assertThat(result).containsExactly(
                new PendingPage(retried.getId(), retried.getSourceUrl(), 2),
                new PendingPage(fresh.getId(), fresh.getSourceUrl(), 0));
    }

    @Test
    @DisplayName("findByArchiveJobIdOrderById projects all of the job's pages onto PageResult, every component, by id")
    void projectsPageResults() {
        UUID job = job();
        page(job, "/a", SUCCEEDED);
        page(job, "/b", FAILED);
        page(job(), "/other-job", SUCCEEDED);

        List<PageResult> result = pages.findByArchiveJobIdOrderById(job, PageResult.class);

        assertThat(result).containsExactly(
                new PageResult("https://example.org/a", SUCCEEDED, "pages/a.html", 200, "text/html; charset=utf-8",
                        "https://example.org/a/final", 1, null),
                new PageResult("https://example.org/b", FAILED, null, 404, null, null, 1, "HTTP status 404"));
    }

    @Test
    @DisplayName("countPages counts each requested job's pages per status, with no row for a job without pages")
    void countsPagesPerStatus() {
        UUID busy = job();
        page(busy, "/a", PENDING);
        page(busy, "/b", SUCCEEDED);
        page(busy, "/c", SUCCEEDED);
        page(busy, "/d", FAILED);
        UUID failing = job();
        page(failing, "/a", FAILED);
        UUID withoutPages = job();
        page(job(), "/not-requested", PENDING);

        List<PageCounts> counts = pages.countPages(List.of(busy, failing, withoutPages));

        assertThat(counts).containsExactlyInAnyOrder(
                new PageCounts(busy, 1, 2, 1),
                new PageCounts(failing, 0, 0, 1));
    }

    @Test
    @DisplayName("The schema allows a page URL once per job, so re-processing a job cannot record a page twice")
    void allowsAPageUrlOncePerJob() {
        UUID job = job();
        page(job, "/a", SUCCEEDED);
        page(job(), "/a", PENDING); // the same URL in another job is fine

        assertThatThrownBy(() -> page(job, "/a", PENDING))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_archive_page_job_source_url");
    }

    private UUID job() {
        return jobs.saveAndFlush(ArchiveJobs.job(T0, ArchiveJobStatus.QUEUED)).getId();
    }

    private ArchivePage page(UUID job, String path, PageStatus status) {
        return pages.saveAndFlush(ArchiveJobs.page(job, path, status));
    }

    private ArchivePage pending(UUID job, String path, int attempts) {
        return pages.saveAndFlush(ArchiveJobs.pending(job, path, attempts));
    }
}
