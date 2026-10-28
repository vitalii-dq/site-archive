package org.archive.sitearchive.archive.api;

import jakarta.validation.Valid;
import org.archive.sitearchive.archive.ArchiveJobStatus;
import org.archive.sitearchive.archive.PageStatus;
import org.archive.sitearchive.archive.api.dto.ArchiveCreatedResponse;
import org.archive.sitearchive.archive.api.dto.ArchiveJobResponse;
import org.archive.sitearchive.archive.api.dto.ArchivePageResponse;
import org.archive.sitearchive.archive.api.dto.CreateArchiveRequest;
import org.archive.sitearchive.web.PagedResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/archives")
class ArchiveController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final ArchiveService archiveService;

    ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @PostMapping
    ResponseEntity<ArchiveCreatedResponse> create(@Valid @RequestBody CreateArchiveRequest request) {
        return ResponseEntity.accepted().body(archiveService.createJob(request));
    }

    @GetMapping("/{id}")
    ArchiveJobResponse get(@PathVariable UUID id) {
        return archiveService.getJob(id);
    }

    @GetMapping
    PagedResponse<ArchiveJobResponse> list(@RequestParam(required = false) ArchiveJobStatus status, Pageable pageable) {
        return archiveService.listJobs(status, pageable);
    }

    @GetMapping("/{id}/pages")
    PagedResponse<ArchivePageResponse> pages(@PathVariable UUID id, @RequestParam(required = false) PageStatus status,
                                             Pageable pageable) {
        return archiveService.listPages(id, status, pageable);
    }

    @GetMapping("/{id}/download")
    ResponseEntity<Resource> download(@PathVariable UUID id) {
        ArchiveDownload download = archiveService.getDownload(id);
        return ResponseEntity.ok()
                .contentType(ZIP)
                .headers(headers -> headers.setContentDisposition(
                        ContentDisposition.attachment().filename(download.fileName()).build()))
                .body(new FileSystemResource(download.file()));
    }
}
