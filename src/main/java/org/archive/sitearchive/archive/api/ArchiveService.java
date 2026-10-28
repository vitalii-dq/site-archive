package org.archive.sitearchive.archive.api;

import org.archive.sitearchive.archive.ArchiveJob;
import org.archive.sitearchive.archive.ArchiveJobRepository;
import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.archive.sitearchive.archive.ArchivePage;
import org.archive.sitearchive.archive.ArchivePageRepository;
import org.archive.sitearchive.archive.PageCounts;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.archive.api.dto.ArchiveCreatedResponse;
import org.archive.sitearchive.archive.api.dto.ArchiveJobResponse;
import org.archive.sitearchive.archive.api.dto.ArchivePageResponse;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.archive.sitearchive.archive.api.error.ArchiveFileMissingException;
import org.archive.sitearchive.archive.api.error.ArchiveNotFoundException;
import org.archive.sitearchive.archive.api.error.ArchiveNotReadyException;
import org.archive.sitearchive.config.ArchiveProperties;
import org.archive.sitearchive.fetch.HttpUrls;
import org.archive.sitearchive.storage.ArchiveStorage;
import org.archive.sitearchive.web.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class ArchiveService {

    private static final Sort NEWEST_JOBS_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Sort PAGES_IN_SITEMAP_ORDER = Sort.by("id");

    private final ArchiveJobRepository jobs;
    private final ArchivePageRepository pages;
    private final ArchiveStorage storage;
    private final ArchiveProperties.Limits limits;
    private final Clock clock;

    ArchiveService(ArchiveJobRepository jobs, ArchivePageRepository pages, ArchiveStorage storage,
                   ArchiveProperties properties, Clock clock) {
        this.jobs = jobs;
        this.pages = pages;
        this.storage = storage;
        this.limits = properties.limits();
        this.clock = clock;
    }

    ArchiveCreatedResponse createJob(CreateArchiveRequest request) {
        String sitemapUrl = HttpUrls.parse(request.sitemapUrl()).orElseThrow().toString();
        int maxPages = request.maxPages() != null ? request.maxPages() : limits.defaultMaxPages();
        return ArchiveCreatedResponse.from(jobs.save(ArchiveJob.queue(sitemapUrl, maxPages, clock.instant())));
    }

    ArchiveJobResponse getJob(UUID jobId) {
        return toResponse(findJob(jobId), pageCounts(List.of(jobId)));
    }

    PagedResponse<ArchiveJobResponse> listJobs(ArchiveJobStatus status, Pageable pageable) {
        Pageable pageRequest = sorted(pageable, NEWEST_JOBS_FIRST);
        Page<ArchiveJob> result = status == null
                ? jobs.findAll(pageRequest)
                : jobs.findByStatus(status, pageRequest);
        Map<UUID, PageCounts> counts = pageCounts(result.map(ArchiveJob::getId).getContent());
        return PagedResponse.from(result, job -> toResponse(job, counts));
    }

    PagedResponse<ArchivePageResponse> listPages(UUID jobId, PageStatus status, Pageable pageable) {
        if (!jobs.existsById(jobId)) {
            throw new ArchiveNotFoundException(jobId);
        }
        Pageable pageRequest = sorted(pageable, PAGES_IN_SITEMAP_ORDER);
        Page<ArchivePage> result = status == null
                ? pages.findByArchiveJobId(jobId, pageRequest)
                : pages.findByArchiveJobIdAndStatus(jobId, status, pageRequest);
        return PagedResponse.from(result, ArchivePageResponse::from);
    }

    ArchiveDownload getDownload(UUID jobId) {
        ArchiveJob job = findJob(jobId);
        if (!job.getStatus().hasArchive()) {
            throw new ArchiveNotReadyException(jobId, job.getStatus());
        }
        Path file = storage.resolve(job.getArchiveFile());
        if (!Files.isRegularFile(file)) {
            throw new ArchiveFileMissingException(jobId);
        }
        return new ArchiveDownload(file, "archive-" + jobId + ".zip");
    }

    private ArchiveJob findJob(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new ArchiveNotFoundException(jobId));
    }

    /** The order is fixed by the API; any sort requested by the lient is ignored. */
    private static Pageable sorted(Pageable pageable, Sort sort) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private static ArchiveJobResponse toResponse(ArchiveJob job, Map<UUID, PageCounts> counts) {
        return ArchiveJobResponse.from(job, counts.getOrDefault(job.getId(), PageCounts.none(job.getId())));
    }

    private Map<UUID, PageCounts> pageCounts(List<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        return pages.countPages(jobIds).stream()
                .collect(Collectors.toMap(PageCounts::archiveJobId, Function.identity()));
    }
}
